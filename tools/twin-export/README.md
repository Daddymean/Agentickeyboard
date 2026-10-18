# Legacy-twin export: decrypt on the training machine (KEYBOARD-026)

The keyboard's **Encrypted export** screen writes one `.aktx` file. It goes to a folder on the phone that you pick, such as Downloads. Keith's decision is that the export stays encrypted on the phone until he copies it to his training machine himself:
- the app uploads nothing;
- it refuses cloud locations;
- it never shares the file automatically.

## Copy it off the phone

Use any manual route you trust, for example USB file transfer or `adb pull /sdcard/Download/twin-export-….aktx`. The file is useless without the passphrase. It is **not** tied to the phone or its Keystore, so anyone who has both the file and the passphrase can open it.

## Decrypt and verify

```sh
python3 -m pip install cryptography          # open source (Apache-2.0/BSD)
python3 decrypt_twin_export.py --verify-only twin-export-20261012-1730.aktx
python3 decrypt_twin_export.py twin-export-20261012-1730.aktx twin-data/
```

- The passphrase is read without echo, or from `TWIN_EXPORT_PASSPHRASE`.
- The script checks every AES-GCM chunk: the tag, the header binding, the order, and that nothing is missing after the final chunk.
- It then checks every file's SHA-256 against `manifest.json`.
- Any failure exits with code 2 and leaves nothing behind.

## Contents

| File | What |
|---|---|
| `twin/messages.jsonl` | Typed messages, `{"at": ms, "package": "...", "text": "..."}`. The text has passed `TwinExportScrubber`: messages with credentials are left out, and identifiers and numbers are replaced with `[ID]` or `[NUM]`. |
| `journal/records.jsonl` | Journal records `{"id", "at", "kind", "body"}` (answers, transcripts, follow-ups, instrument responses, card sorts, voice-script takes, settings). Free text is scrubbed. A field the scrubber rejects is removed and named in `body._redacted`. |
| `audio/<id>.flac` | Saved recordings, bit-exact: lossless FLAC, 48 kHz mono, 16- or 24-bit. They are referenced from `body.audio[].file`. |
| `manifest.json` | Format, creation time, counts, and the SHA-256 and size of every file. |

## Format

The format is documented in `decrypt_twin_export.py` and `PassphraseArchive.kt`:
- **Key derivation**: PBKDF2-HMAC-SHA256 with 600,000 iterations and a 16-byte random salt, over the passphrase in NFC as UTF-8.
- **Encryption**: AES-256-GCM in 1 MiB chunks.
- **IV**: an 8-byte random prefix followed by the chunk index.
- **Associated data**: the header, the chunk index and the final flag.

## Dead-man release archives

A release archive (header kdf 2) is opened with `open_twin_release.py` and two share cards, not with a passphrase. See [RELEASE.md](RELEASE.md).
