#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Decrypt, verify and unpack an Agentic Keyboard legacy-twin export (.aktx).

KEYBOARD-026. Runs on the training machine, never on the phone. It needs only
Python 3.9+ and the open-source `cryptography` package (pip install cryptography).

    python3 decrypt_twin_export.py twin-export-20261012-1730.aktx out/
    python3 decrypt_twin_export.py --verify-only twin-export-20261012-1730.aktx

It asks for the passphrase without echoing it (or reads it from the environment
variable TWIN_EXPORT_PASSPHRASE for scripted use). It checks every chunk's
authentication tag, the header binding, the chunk order and the end marker, then
checks the SHA-256 of every file against manifest.json. Any failure stops with a
non-zero exit code. A failed run leaves nothing behind.

Format v1 (big-endian):
  header = "AKTX" | version u8 = 1 | kdf u8 = 1 (PBKDF2-HMAC-SHA256)
           | iterations u32 | salt[16] | nonce_prefix[8]
  chunk  = final u8 | length u32 | AES-256-GCM(ciphertext || tag[16])
  key    = PBKDF2-HMAC-SHA256(NFC(passphrase) as UTF-8, salt, iterations, 32)
  iv_i   = nonce_prefix || u32(i)
  aad_i  = header || u32(i) || final u8
The plaintext is a ZIP: twin/messages.jsonl, journal/records.jsonl,
audio/<id>.flac and manifest.json.
"""
import argparse
import getpass
import hashlib
import json
import os
import shutil
import struct
import sys
import tempfile
import unicodedata
import zipfile

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:  # pragma: no cover
    sys.exit("This script needs the 'cryptography' package: pip install cryptography")

MAGIC = b"AKTX"
HEADER_LEN = 4 + 1 + 1 + 4 + 16 + 8
CHUNK_BYTES = 1 << 20
FORMAT = "agentickeyboard-twin-export-v1"


class ExportError(Exception):
    pass


def _read_exact(f, n):
    b = f.read(n)
    if len(b) != n:
        raise ExportError("archive is truncated")
    return b


def decrypt_stream(src, dst, passphrase):
    """Decrypts the .aktx in file object src into file object dst (the ZIP)."""
    header = _read_exact(src, HEADER_LEN)
    if header[:4] != MAGIC:
        raise ExportError("not a twin export")
    version, kdf, iterations = header[4], header[5], struct.unpack(">I", header[6:10])[0]
    if version != 1 or kdf != 1:
        raise ExportError("unsupported version %d / kdf %d" % (version, kdf))
    if not 100_000 <= iterations <= 10_000_000:
        raise ExportError("bad iteration count")
    salt, prefix = header[10:26], header[26:34]
    pw = unicodedata.normalize("NFC", passphrase).encode("utf-8")
    key = hashlib.pbkdf2_hmac("sha256", pw, salt, iterations, 32)
    aes = AESGCM(key)
    index = 0
    while True:
        flag = src.read(1)
        if not flag:
            raise ExportError("archive is truncated (no final chunk)")
        final = flag[0]
        if final not in (0, 1):
            raise ExportError("bad chunk")
        (length,) = struct.unpack(">I", _read_exact(src, 4))
        if not 16 <= length <= CHUNK_BYTES + 16:
            raise ExportError("bad chunk length")
        ct = _read_exact(src, length)
        iv = prefix + struct.pack(">I", index)
        aad = header + struct.pack(">I", index) + bytes([final])
        try:
            dst.write(aes.decrypt(iv, ct, aad))
        except Exception:
            raise ExportError("wrong passphrase or damaged archive (chunk %d)" % index) from None
        index += 1
        if final:
            if src.read(1):
                raise ExportError("data after the last chunk")
            return index


def verify_zip(zf):
    try:
        manifest = json.loads(zf.read("manifest.json"))
    except KeyError:
        raise ExportError("manifest.json missing") from None
    if manifest.get("format") != FORMAT:
        raise ExportError("unknown format %r" % manifest.get("format"))
    files = manifest["files"]
    names = {n for n in zf.namelist() if n != "manifest.json"}
    if names != set(files):
        raise ExportError("file list does not match the manifest")
    for name in sorted(names):
        h = hashlib.sha256()
        size = 0
        with zf.open(name) as f:
            for block in iter(lambda: f.read(1 << 16), b""):
                h.update(block)
                size += len(block)
        if h.hexdigest() != files[name]["sha256"] or size != files[name]["bytes"]:
            raise ExportError("hash mismatch: " + name)
    return manifest


def safe_extract(zf, out_dir):
    root = os.path.realpath(out_dir)
    for info in zf.infolist():
        target = os.path.realpath(os.path.join(root, info.filename))
        if not target.startswith(root + os.sep):
            raise ExportError("unsafe path in archive: " + info.filename)
    zf.extractall(root)


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("archive")
    ap.add_argument("out_dir", nargs="?")
    ap.add_argument("--verify-only", action="store_true", help="check everything, write nothing")
    args = ap.parse_args(argv)
    if not args.verify_only and not args.out_dir:
        ap.error("out_dir is required unless --verify-only")
    passphrase = os.environ.get("TWIN_EXPORT_PASSPHRASE") or getpass.getpass("Passphrase: ")
    tmp_parent = args.out_dir if args.out_dir else None
    if tmp_parent:
        os.makedirs(tmp_parent, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=tmp_parent) as tmp:
        zpath = os.path.join(tmp, "export.zip")
        try:
            with open(args.archive, "rb") as src, open(zpath, "wb") as dst:
                chunks = decrypt_stream(src, dst, passphrase)
            with zipfile.ZipFile(zpath) as zf:
                manifest = verify_zip(zf)
                if not args.verify_only:
                    staging = os.path.join(tmp, "unpacked")
                    os.makedirs(staging)
                    safe_extract(zf, staging)
                    for name in os.listdir(staging):
                        dest = os.path.join(args.out_dir, name)
                        if os.path.exists(dest):
                            raise ExportError("refusing to overwrite " + dest)
                    for name in os.listdir(staging):
                        shutil.move(os.path.join(staging, name), os.path.join(args.out_dir, name))
        except (ExportError, zipfile.BadZipFile) as e:
            print("FAILED: %s" % e, file=sys.stderr)
            return 2
    c = manifest["counts"]
    print("OK: %d chunks verified, %d files match manifest.json" % (chunks, len(manifest["files"])))
    print("    %(messages)d messages (%(messages_dropped)d dropped by the scrubber), "
          "%(journal_records)d journal records (%(fields_redacted)d fields redacted), "
          "%(recordings)d recordings" % c)
    if not args.verify_only:
        print("    unpacked into " + os.path.abspath(args.out_dir))
    return 0


if __name__ == "__main__":
    sys.exit(main())
