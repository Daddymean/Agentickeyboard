#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Open one time capsule from an opened twin release (KEYBOARD-036).

Two of the three key holders run this together on a computer, when a capsule's milestone
has come:

    python3 open_time_capsule.py twin-data/capsules/0123456789abcdef.json twin-release-key-set1.json \\
        --card card-guardian-1.txt --card card-kid-1.txt [--confirm "Guardian 1" --confirm "Kid 1"] opened/

The script:
  1. recombines the two cards and unwraps the release data key (DEK), exactly like
     open_twin_release.py;
  2. checks the capsule's rule. For a date, today (or --today) must be on or after it. For an
     event (graduation, wedding, ...), two different current key holders must each name
     themselves with --confirm;
  3. derives only this capsule's key, HKDF-SHA256(DEK, salt=archive id,
     info="akdm-capsule-v1|<archive id>|<capsule id>"), and decrypts it (AES-256-GCM).
     It writes message.md and any FLAC recordings to out_dir/<capsule id>/.
The archive is never re-encrypted, and no other capsule is opened. The rule is enforced by
this script and the holders' word, not by cryptography: see RELEASE.md, "Time capsules".
Any failure exits 2 and writes nothing.
"""
import argparse
import base64
import datetime
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from decrypt_twin_export import AESGCM, ExportError  # noqa: E402
from open_twin_release import parse_card, unwrap  # noqa: E402

from cryptography.hazmat.primitives import hashes  # noqa: E402
from cryptography.hazmat.primitives.kdf.hkdf import HKDF  # noqa: E402

INFO = "akdm-capsule-v1"


def capsule_key(dek, archive_id_hex, capsule_id):
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=bytes.fromhex(archive_id_hex),
                info=("%s|%s|%s" % (INFO, archive_id_hex, capsule_id)).encode("ascii")).derive(dek)


def check_rule(capsule, key_file, confirms, today):
    u = capsule["unlock"]
    if u["type"] == "date":
        opens = datetime.date.fromisoformat(u["date"])
        if today < opens:
            raise ExportError("this capsule opens on %s" % opens)
        return "date %s has come" % opens
    holders = {h["label"] for h in key_file["holders"]}
    named = set(confirms)
    if len(named) < 2 or not named <= holders:
        raise ExportError("event '%s': two different current key holders must each --confirm (holders: %s)" % (
            u["event"], ", ".join(sorted(holders))))
    return "event '%s' confirmed by %s" % (u["event"], " and ".join(sorted(named)))


def open_capsule(capsule, key_file, cards, confirms, today):
    reason = check_rule(capsule, key_file, confirms, today)
    dek = unwrap(key_file, cards)
    s = capsule["sealed"]
    aad = ("%s|%s|%s|%s" % (INFO, key_file["archive_id"], capsule["id"], capsule["kid"])).encode("utf-8")
    try:
        plain = AESGCM(capsule_key(dek, key_file["archive_id"], capsule["id"])).decrypt(
            bytes.fromhex(s["iv"]), bytes.fromhex(s["ciphertext"]), aad)
    except Exception:
        raise ExportError("the cards don't open this capsule (wrong key file or archive?)") from None
    return json.loads(plain.decode("utf-8")), reason


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("capsule")
    ap.add_argument("key_file")
    ap.add_argument("out_dir")
    ap.add_argument("--card", action="append", default=[])
    ap.add_argument("--confirm", action="append", default=[], help="a key holder confirming the event (give two)")
    ap.add_argument("--today", help="YYYY-MM-DD, for testing")
    args = ap.parse_intermixed_args(argv)
    try:
        with open(args.capsule, encoding="utf-8") as f:
            capsule = json.load(f)
        if "sealed" not in capsule:
            raise ExportError("this capsule isn't sealed (it came from Keith's own export)")
        with open(args.key_file) as f:
            key_file = json.load(f)
        cards = [parse_card(open(c).read() if os.path.exists(c) else c) for c in args.card]
        today = datetime.date.fromisoformat(args.today) if args.today else datetime.date.today()
        content, reason = open_capsule(capsule, key_file, cards, args.confirm, today)
        dest = os.path.join(args.out_dir, capsule["id"])
        if os.path.exists(dest):
            raise ExportError("refusing to overwrite " + dest)
        os.makedirs(dest)
        lines = ["# %s (for %s)" % (content["title"], content["kid"]), "", "Opened %s: %s." % (today, reason), "", content.get("text", "")]
        for a in content.get("audio", []):
            name = a["name"]
            if not name.replace("-", "").isalnum():
                raise ExportError("bad recording name")
            with open(os.path.join(dest, name + ".flac"), "wb") as f:
                f.write(base64.b64decode(a["flac"]))
            lines += ["", "Recording: %s.flac" % name]
        with open(os.path.join(dest, "message.md"), "w", encoding="utf-8") as f:
            f.write("\n".join(lines) + "\n")
    except (ExportError, ValueError, KeyError, OSError) as e:
        print("FAILED: %s" % e, file=sys.stderr)
        return 2
    print("Opened '%s' for %s (%s) into %s" % (capsule["title"], capsule["kid"], reason, os.path.abspath(dest)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
