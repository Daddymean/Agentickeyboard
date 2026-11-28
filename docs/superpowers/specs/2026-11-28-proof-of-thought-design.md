# Proof of Thought: signed receipts of how a message was written

Status: **proposal, needs Keith.** It covers crypto and keystroke data, so every code slice opens as a draft **HOLD for Keith** with a second reviewer (ADR-0007).
Claim: #agent-board, 2026-11-28 (Claude).

## 1. Problem

Readers can no longer tell whether a message was written by a person or a model. Today's tools inspect the finished text: detectors guess from style, and watermarks only work when the model vendor cooperates. Neither saw the text being written.

A keyboard did see it. Agentickeyboard is also an AI keyboard, so it knows exactly which characters it inserted from its own suggestions. That makes it the one honest witness for both halves: what a finger typed and what the AI wrote.

## 2. What it does

On request, the keyboard produces a **receipt**: a small signed statement about the text currently in the field. It holds:

1. **A hash of the text**, never the text.
2. **The origin mix**: the share of the final text that was typed, swiped, AI-accepted, pasted, or not seen by the keyboard at all.
3. **Coarse composition stats**: bucketed numbers describing how the text was written (revision, pauses, rhythm), never raw timings.
4. **A signature** from a hardware-backed Android Keystore key. Android key attestation proves the key lives in a real device's secure hardware and belongs to this app's package and signing certificate.

An offline verifier checks the receipt against the text the reader received and shows something like:

> ✅ Signed on a hardware-attested Android device by Agentickeyboard (release build)
> Origin: 81% typed · 6% swiped · 13% AI-accepted · 0% pasted · 0% unwitnessed
> Composition: high revision · irregular rhythm · 4 long pauses (pattern consistent with composing, not retyping)
> ⚠️ Evidence, not proof. See "What a receipt can't prove".

### Non-goals

- Proving the identity of a person. Receipts are pseudonymous (§7.3).
- Detecting AI in text written outside this keyboard.
- Uploading anything. There is no server, account or network path.
- Watermarking or steganography in the text.

## 3. What a receipt can and can't prove

| Claim | Strength | Why |
|---|---|---|
| The text matches what the keyboard signed | Strong | SHA-256 plus an ECDSA signature |
| It was signed by a genuine install of this app on real secure hardware | Strong | Key attestation chain to Google's hardware attestation roots, with the app package and signing-cert digest pinned |
| The AI-accepted share is at least X% | Strong, a lower bound | The keyboard inserts AI text itself and can't miscount it. AI text the user retyped by hand counts as typed, which is why composition stats exist |
| A person composed it rather than transcribing it | **Weak to medium** | Statistical. Retyping is steady and forward-only; composing is bursty and revised. A patient forger can imitate it |
| The touches came from a finger | **Medium** | Accessibility `dispatchGesture` and other injection paths produce unnaturally clean touch data, which the keyboard can detect (§5.3). This raises the cost of forgery; it doesn't make it impossible |

Every verifier output carries the caveat line. The UI never uses the word "proof" on its own. The feature name is a nod, not a claim.

## 4. Origin ledger

### 4.1 Model

For each editor session, the keyboard keeps an **origin array** aligned with its view of the field text (the LAB-009 `EditorTextMirror`). It holds one origin tag per character, stored as run-length segments:

| Tag | Source |
|---|---|
| `TYPED` | Key taps committed by `onKeyPress` |
| `SWIPED` | Swipe-decoded words |
| `ASSISTED` | A human choice of a keyboard-proposed word: suggestion-chip picks and autocorrect replacements. Reported separately so the typed share isn't inflated |
| `AI` | Gemini or Gemma results applied (rewrite, compose, continue, reply), quick answers, transforms |
| `PASTED` | Clipboard history, snippets, templates, the 📋 chip |
| `UNWITNESSED` | Anything the keyboard didn't insert itself: app-side edits, a physical keyboard, another IME, text already in the field when the session started |

### 4.2 Hook points

`AgenticKeyboardService` already marks every insert path with a `// twin: inserted (...)` comment (17 sites). Each of these becomes a call that tags the inserted range: `ledger.insert(range, origin)`. Deletions shrink the array, whatever deleted them. When `onUpdateSelection` (or a fresh read) shows a change the keyboard didn't make, the changed span is retagged `UNWITNESSED`.

The ledger is updated alongside `TwinTrackingInputConnection`, not inside it. That keeps twin code untouched and means the ledger has no persistence at all.

### 4.3 Reconciliation at receipt time

Before signing, the keyboard reads the field fresh (the same rule #232 set for edit-sizing paths) and diffs it against the mirror. Spans that don't match become `UNWITNESSED`. If the field is over 20,000 characters, or the keyboard can't read it whole, no receipt is issued. A receipt never covers text the keyboard couldn't read.

### 4.4 Lifetime

The ledger lives in memory only and is dropped in `onFinishInput`, on process death and when the session switches fields. It is never written to Room, writing logs, the twin or backups.

## 5. Composition stats

### 5.1 Collected (in memory, per session)

These come from timestamps of the keyboard's own key events. Only the derived values below are kept, as rolling counters.

- `revision_ratio`: characters deleted ÷ final characters.
- `nonlinear_edits`: edits made away from the end of the text, per 100 final characters.
- `long_pauses`: gaps over 2 s between keystrokes, per 100 characters.
- `rhythm_cv`: coefficient of variation of the gaps between keystrokes, computed online with Welford's algorithm. No intervals are stored.
- `session_minutes`.

### 5.2 Signed (bucketed)

Each value is signed only as a 3–4 level bucket (`low`, `mid`, `high`, `very_high`). Bucketing is the privacy mechanism: precise typing-rhythm numbers act as a biometric fingerprint, and buckets don't. The thresholds are versioned in the receipt (`stats_v`) so they can be retuned.

### 5.3 Touch-authenticity signal

For key taps, the keyboard tracks the spread of `MotionEvent` contact size, pressure and the offset of the touch point from the key's centre. Injected gestures tend to report constant size and pressure and land on near-perfect centres. Only one signed field comes out of this: `touch: natural | uniform | n/a`. `uniform` doesn't block a receipt; the verifier shows it as a warning.

## 6. Receipt format

### 6.1 Payload

The payload is canonical JSON: keys sorted, no whitespace, UTF-8, integers only. It uses no new dependencies.

```json
{"ai":13,"as":0,"kid":"3f9a…(16 hex)","n":"<96-bit nonce, b64url>","pa":0,"st":{"lp":"mid","nl":"high","rc":"high","rv":"high","sm":"low","touch":"natural"},"stats_v":1,"sw":6,"t":"2026-11-28","th":"<SHA-256 b64url>","ty":81,"uw":0,"v":1}
```

- `th` is the SHA-256 of the normalized text (§6.3).
- Shares are integer percentages of final characters, rounded so they sum to 100.
- `t` is the date by default. Minute precision is an opt-in setting, because exact times reveal when someone was typing.
- `kid` is the first 8 bytes of SHA-256(public key).

### 6.2 Token

```
⟦pot1.<b64url payload>.<b64url raw r‖s signature>⟧
```

The signature is ES256 (ECDSA P-256 / SHA-256) over the payload bytes. A token is roughly 300 characters. The verifier strips any `⟦pot1.…⟧` token from the text before hashing, so a token can be appended to the message it covers.

### 6.3 Text normalization

Messengers rewrite text, so the hash is taken over normalized text: Unicode NFC, `\r\n` and `\r` turned into `\n`, trailing whitespace stripped from each line, and leading and trailing blank lines removed. Smart quotes and markdown are **not** normalized: if a messenger changes them, the verifier reports "text changed" rather than guessing. Known messenger rewrites go in a verifier compatibility table later, not in the signed format.

### 6.4 Key card

The attestation chain is 3–5 KB, too big for every message. It travels separately as a **key card**: the public key, the attestation certificate chain and `kid`. The user shares it once (as a file or QR code, or on a profile). The verifier caches key cards by `kid`. There is also a **self-contained** export (token plus key card as a `.pot` file) for one-off verification.

## 7. Keys

### 7.1 Generation

Keys are EC P-256 keys from `KeyGenParameterSpec` with `PURPOSE_SIGN`, `setAttestationChallenge(kid-binding nonce)`, StrongBox where available (otherwise TEE) and `setUserAuthenticationRequired(false)`. Signing a receipt is already an explicit action. The keys are non-exportable and excluded from backups, following the existing `GeminiKeyStore` pattern.

**Device IDs are not requested.** Without ID attestation, the chain reveals security level, OS version and patch level, verified-boot state, and the app package and signing digest. It doesn't reveal the model, serial number or IMEI. The key-card share sheet says this.

### 7.2 What the verifier pins

- The chain ends at a published Google hardware attestation root.
- `attestationApplicationId` is `io.github.daddymean.agentickeyboard` with the **release** signing-cert digest. Debug or other signatures show "debug or unknown build: not trustworthy".
- Security level is `StrongBox` or `TrustedEnvironment`. `Software` is rejected.
- Verified boot is `Verified`. Anything else shows a warning, not a failure.
- Revocation is checked against Google's attestation status list only when the verifier is online. Offline, it says "revocation not checked".

### 7.3 Linkability

One long-lived key would link every receipt a person ever shares, and that is a tracking risk. Recommendation: **one key per destination app** (keyed by editor package), plus a "new identity" button that rotates them. Linkability within one app is usually what people want there (reputation in a community). Linking across apps is opt-in. This is open question Q2.

## 8. User experience

- The feature is **off by default**: Settings → Proof of Thought.
- When it's on, a 🧾 action appears in the command row. Tapping it reconciles the field (§4.3) and shows the origin mix with two choices: **Append receipt** (inserts the token at the end of the field, visible) or **Copy receipt**. The keyboard never adds a receipt without that tap.
- Sensitive fields (`EditorPrivacy.isSensitiveEditor`, which covers passwords, incognito, OTP, card and ID fields) get no 🧾 action, and the ledger doesn't run there at all.
- The Trust Prism banner gets no new state. Receipts are a separate, explicit action.
- The companion app gets a **Key card** screen: share, rotate, and an explanation of what the chain reveals.

## 9. Verifier

- `tools/proof-of-thought/verify.py`: a CLI using `cryptography` (Apache-2.0/BSD). Inputs are text plus token (plus key card or cache). Output is JSON or human-readable text.
- `tools/proof-of-thought/verifier.html`: one static file using WebCrypto for ECDSA and a small, auditable DER parser (about 200 lines, no dependency). It runs offline from disk and makes no network calls unless the user turns on the revocation check.
- Both run the same golden test vectors (§10).

## 10. Testing

- **Ledger (JVM)**: property tests over random sequences of mixed insert, delete, replace and external edits, asserting that the origin array always has the same length as the mirror text, that only keyboard paths can produce non-`UNWITNESSED` tags, and that reconciliation never raises a share.
- **Canonical serialization**: golden vectors for payload bytes, normalization (CRLF, NFC, emoji, RTL) and token parsing.
- **Signing**: a JVM software EC key plus a test CA chain for the format. A Robolectric smoke test for the Keystore wiring where it's supported. The real StrongBox path is checked on Keith's phone (smoke checklist item).
- **Cross-verifier**: vectors produced by the Kotlin side must verify in Python and in the HTML verifier (headless Chromium in CI).
- **Privacy**: an extension of `TwinCloudIsolationTest`-style guards asserting that the ledger and stats classes hold no reference to Room, writing logs, the twin or network code.
- Per AGENTS.md: `tools/cloud-gradle.sh testDebugUnitTest lintDebug` before every review.

## 11. Slices (one PR each)

1. **Origin ledger and composition counters.** Pure Kotlin, hooked into the insert sites, plus a debug-only overlay of the live mix. No crypto, nothing persisted. HOLD, because it touches the input-connection path next to the twin wrapper.
2. **Receipt, keys and UI.** Keystore attestation, signing, the 🧾 action, the key card screen and the settings toggle. HOLD, second reviewer (crypto).
3. **Verifier.** Python CLI, static HTML and cross-verified golden vectors. Normal review (off-device tooling, no app data).
4. **Touch-authenticity signal and stats tuning.** Recalibrate bucket thresholds against Keith's own on-device runs (counts only, kept on the phone).

## 12. Privacy rules: how this meets each

| Rule | How |
|---|---|
| Never learn from sensitive fields | The ledger is disabled there and no receipt action is shown |
| No user data in logs | The ledger and signing log exception class names only |
| On-device only | No network in the app. The verifier is offline by default |
| Keys in Keystore, never in the repo | Non-exportable Keystore keys, excluded from backup |
| Open-source dependencies only | App: platform APIs only. Verifier: `cryptography` (Apache-2.0/BSD) |
| Twin isolation | The ledger never feeds the twin. The twin's tracked connection is not modified |

## 13. Open questions for Keith

1. **Go or no-go**, and whether it ships in the keyboard's main flavor or behind a lab flag first.
2. **Key scope**: one key per app with rotation (recommended), one global key, or a fresh key per receipt (unlinkable, but every message needs its own 3–5 KB key card).
3. **Swiped and assisted text**: should they count toward a single headline "human" share? The proposal reports them separately and the verifier shows `typed + swiped` as "human-input".
4. **Release signing digest**: the verifier must pin it. Keith decides whether it goes into the repo; it's a public fingerprint, not a secret.

## 14. Rejected alternatives

- **Zero-width-character watermarks**: many apps strip them, and they would carry hidden data the sender can't see.
- **Server-side registry of receipts**: breaks the on-device rule and creates a honeypot of who wrote what and when.
- **Signing raw keystroke timings**: a biometric fingerprint. Bucketed stats keep the signal without one.
- **Making "humanness" a model score**: opaque and gameable. The ledger records facts; only §5 is statistical, and it's labelled that way.
