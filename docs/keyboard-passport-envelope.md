# Keyboard Passport: Versioned Portable Envelope

## Purpose

Keyboard Passport gives users ownership of Lumina's local personal model without
requiring a Lumina account or hidden cloud transfer. This first slice defines the
portable file format and validates it before any repository data is changed.

File selection, import preview screens, merge/replace application, and sharing
are deliberately deferred to the companion-app slice.

## Version 1 envelope

A passport is a UTF-8 JSON envelope with:

- a stable format identifier and integer version,
- creation time,
- explicit included-category flags,
- record counts for preview,
- a SHA-256 checksum of the plaintext payload,
- payload encoding metadata,
- optional encryption metadata,
- and a Base64 payload.

The payload has its own schema version so future envelope and data migrations can
advance independently.

## Portable categories

Version 1 can carry:

- global persona preference,
- learned vocabulary,
- learned corrections,
- shortcuts,
- custom slash commands,
- per-app personas,
- and writing logs.

Writing logs are excluded by default because they contain raw user-authored
text. They require explicit category inclusion. Free-text fields pass through
the existing local redactor by default; callers may deliberately disable that
when creating a user-owned export.

## Encryption

When a passphrase is supplied, the payload uses:

- AES-256-GCM authenticated encryption,
- PBKDF2-HMAC-SHA256 key derivation,
- 210,000 PBKDF2 iterations,
- a fresh 16-byte random salt,
- a fresh 12-byte random nonce,
- and a 128-bit authentication tag.

The passphrase is used only during creation or opening and is never included in
the envelope. Wrong passphrases and modified ciphertext fail safely with no
partial payload.

Unencrypted passports remain checksummed. A mismatch between the payload,
checksum, or advertised record counts rejects the file before application.

## Preview and compatibility

`KeyboardPassport.inspect` reads only envelope metadata and can show category
and count summaries without decrypting the payload. Encrypted files clearly
report that a passphrase is required.

`KeyboardPassport.open` returns one of:

- success with a validated in-memory payload,
- passphrase required with preview metadata,
- or an invalid result with a non-sensitive explanation.

Future envelope versions remain inspectable but are rejected as incompatible
until a migration is implemented.

## Legacy exports

Existing raw JSON and Base64 exports from `PersonalModelSerializer` remain
readable. They are represented as version 0 previews and converted into the new
in-memory payload shape. Legacy files contain only the categories supported by
the old serializer.

## Privacy boundary

This slice:

- performs all work locally,
- opens no files itself,
- writes nothing to Room or SharedPreferences,
- makes no network requests,
- does not persist passphrases or decrypted payloads,
- excludes raw writing logs by default,
- and never imports data merely because a file was inspected.

The companion-app import UI must continue this boundary by showing counts and
categories before applying, distinguishing merge from replace, and requiring a
separate confirmation before any local data changes.
