#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Open the legacy twin's dead-man release archive (KEYBOARD-027).

Two of the three key holders run this together on a computer, never on Keith's phone:

    python3 open_twin_release.py twin-release-2026-10-17.aktx twin-release-key-set1.json \\
        --card card-guardian-1.txt --card card-kid-1.txt  twin-data/

Each --card is a share-card file or the card text itself (AKDM1-...). The script:
  1. checks each card's checksum and that both cards belong to the key file's set;
  2. recombines the key-encryption key with Shamir's secret sharing over GF(2^8);
  3. unwraps the archive's data key (AES-256-GCM, bound to archive id, set and epoch);
  4. checks the archive id, then decrypts, verifies (every chunk and every SHA-256 in
     manifest.json) and unpacks the archive, exactly like decrypt_twin_export.py.
Any failure exits 2 and leaves nothing behind. Needs Python 3.9+ and the open-source
`cryptography` package. Use --verify-only to check without writing anything.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decrypt_twin_export import AESGCM, ExportError, read_header, report, unpack  # noqa: E402

KEY_FILE_FORMAT = "agentickeyboard-twin-release-key-v1"
B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

# ---- GF(2^8) Shamir, same field as the app (AES polynomial 0x11B, generator 3) ----
EXP = [0] * 512
LOG = [0] * 256
_x = 1
for _i in range(255):
    EXP[_i] = _x
    LOG[_x] = _i
    _x ^= _x << 1
    if _x & 0x100:
        _x ^= 0x11B
for _i in range(255, 512):
    EXP[_i] = EXP[_i - 255]


def gf_mul(a, b):
    return 0 if a == 0 or b == 0 else EXP[LOG[a] + LOG[b]]


def gf_div(a, b):
    if b == 0:
        raise ZeroDivisionError
    return 0 if a == 0 else EXP[LOG[a] + 255 - LOG[b]]


def combine(shares):
    """shares: list of (x, bytes). Lagrange interpolation at 0."""
    xs = [x for x, _ in shares]
    if len(set(xs)) != len(xs) or any(not 1 <= x <= 255 for x in xs):
        raise ExportError("duplicate or bad share")
    n = len(shares[0][1])
    out = bytearray(n)
    for i in range(n):
        acc = 0
        for a, (xa, ya) in enumerate(shares):
            num, den = 1, 1
            for b, (xb, _) in enumerate(shares):
                if a != b:
                    num = gf_mul(num, xb)
                    den = gf_mul(den, xa ^ xb)
            acc ^= gf_mul(ya[i], gf_div(num, den))
        out[i] = acc
    return bytes(out)


def b32decode(s):
    buf = bits = 0
    out = bytearray()
    for c in s:
        v = B32.find(c)
        if v < 0:
            raise ExportError("bad character in share")
        buf = (buf << 5) | v
        bits += 5
        if bits >= 8:
            out.append((buf >> (bits - 8)) & 0xFF)
            bits -= 8
    return bytes(out)


def parse_card(text):
    # A card file has the card on its own line; typed card text may have spaces or breaks.
    candidates = [line.strip().upper() for line in text.splitlines()] + [re.sub(r"\s+", "", text).upper()]
    token = next((c for c in candidates if re.fullmatch(r"AKDM1-[A-Z0-9-]+", c)), None)
    if token is None:
        raise ExportError("no share card found")
    parts = token.split("-")
    if len(parts) != 7:
        raise ExportError("malformed share card")
    body = "-".join(parts[:6])
    if hashlib.sha256(body.encode("ascii")).digest()[:4].hex().upper() != parts[6]:
        raise ExportError("share card check failed (typo?)")
    k, n = (int(v) for v in parts[3].split("OF"))
    return {"set_id": parts[1].lower(), "epoch": int(parts[2]), "k": k, "n": n, "x": int(parts[4]), "y": b32decode(parts[5])}


def unwrap(key_file, cards):
    if key_file.get("format") != KEY_FILE_FORMAT:
        raise ExportError("not a twin release key file")
    epoch, set_id = key_file["epoch"], key_file["set_id"]
    if len(cards) < key_file["threshold"]:
        raise ExportError("need %d share cards" % key_file["threshold"])
    for c in cards:
        if c["epoch"] != epoch or c["set_id"] != set_id:
            raise ExportError("a share card is from a different key-holder set (old card or wrong key file)")
    kek = combine([(c["x"], c["y"]) for c in cards])
    w = key_file["wrap"]
    aad = ("akdm-wrap-v1|%s|%d|%s" % (key_file["archive_id"], epoch, set_id)).encode("ascii")
    try:
        return AESGCM(kek).decrypt(bytes.fromhex(w["iv"]), bytes.fromhex(w["ciphertext"]), aad)
    except Exception:
        raise ExportError("the cards don't open this key file") from None


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("archive")
    ap.add_argument("key_file")
    ap.add_argument("out_dir", nargs="?")
    ap.add_argument("--card", action="append", default=[], help="share card file or text (give two)")
    ap.add_argument("--verify-only", action="store_true")
    # Intermixed, so out_dir may follow the --card options on every Python 3 version.
    args = ap.parse_intermixed_args(argv)
    if not args.verify_only and not args.out_dir:
        ap.error("out_dir is required unless --verify-only")
    try:
        cards = [parse_card(open(c).read() if os.path.exists(c) else c) for c in args.card]
        with open(args.key_file) as f:
            key_file = json.load(f)
        with open(args.archive, "rb") as f:
            header = read_header(f)
        if header[5] != 2 or header[10:26].hex() != key_file.get("archive_id"):
            raise ExportError("this key file belongs to a different archive")
        dek = unwrap(key_file, cards)
        chunks, manifest = unpack(args.archive, args.out_dir, args.verify_only, data_key=dek)
    except (ExportError, zipfile.BadZipFile, ValueError, KeyError, OSError) as e:
        print("FAILED: %s" % e, file=sys.stderr)
        return 2
    print("Key holders' cards accepted (set %d: %s)." % (key_file["epoch"], ", ".join(h["label"] for h in key_file["holders"])))
    rules = key_file.get("policy", {}).get("use_rules") or []
    if rules:
        print("Use rules from Keith (KEYBOARD-033):")
        for r in rules:
            print("  - " + r)
    report(chunks, manifest, None if args.verify_only else args.out_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
