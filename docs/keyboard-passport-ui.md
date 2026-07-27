# Keyboard Passport companion flow

## Purpose

The Style Hub now exposes Keyboard Passport as a user-owned file transfer for Lumina's local personal model. It does not require a Lumina account, background sync, cloud storage, contact access, or network access.

The existing telemetry JSON/Base64 card remains available for backward compatibility. Keyboard Passport is the supported device-migration path because it adds versioning, integrity checks, optional authenticated encryption, metadata preview, and explicit import behavior.

## Export flow

1. The user chooses whether to encrypt the passport.
2. When encryption is enabled, the user enters a passphrase that Lumina never stores and cannot recover.
3. Sensitive-identifier redaction is enabled by default.
4. Raw writing logs are excluded by default and require a separate switch.
5. Lumina builds the passport locally and opens Android's system document picker.
6. The selected destination receives the file. Lumina does not retain the URI or upload the file.

The default passport includes:

- global persona preference,
- the complete learned vocabulary table,
- learned corrections,
- shortcut templates,
- custom slash commands,
- per-app persona mappings.

The transfer service deliberately reads the complete vocabulary table rather than the repository's bounded 150-word prediction shelf.

## Import flow

Selecting a file never mutates local data. The UI first reads a maximum of 5 MB and shows the envelope's version, encryption state, categories, and record counts.

Encrypted passports require a passphrase before the payload can be authenticated and inspected. Wrong passphrases, modified ciphertext, checksum failures, record-count mismatches, malformed files, and unsupported future versions fail closed.

After verification, the user chooses one mode:

### Merge

- vocabulary counts are added with overflow protection and `lastUsed` keeps the newer value;
- imported corrections win matching typo conflicts and counts are added;
- imported shortcuts win matching shortcut conflicts;
- imported custom commands win matching token conflicts;
- imported app personas win matching package-name conflicts;
- writing logs are appended with exact-record de-duplication;
- an included global persona replaces the current global persona.

### Replace included

Only categories explicitly listed in the passport are replaced. Categories absent from the file are untouched. An explicitly included category with zero records intentionally clears that category.

The UI requires a second confirmation after the mode is selected. Replace is never inferred from file contents and no automatic rollback is claimed.

## Privacy boundary

- Passphrases live only in Compose state and are cleared after a successful import.
- Decrypted payloads and selected file contents live only in memory.
- No passport data is logged, uploaded, analyzed by Gemini, or tied to Keyboard Mastery.
- Android's Storage Access Framework grants access only to the file selected by the user.
- Raw writing logs stay opt-in because they may contain full previously analyzed text.

## Current transactional boundary

Import planning is complete and deterministic before writes begin. Room category updates are then applied locally in a fixed order. A later hardening slice may wrap the multi-category application in one Room transaction if import interruption recovery becomes a release requirement.