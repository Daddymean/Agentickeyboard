# Release Candidate Test Matrix

This matrix is the handoff from feature construction to stabilization. Run the automated gates first, then the device checks below. Record device/API level, build SHA, pass/fail, and a short note for every failure.

## Automated gates

Required on every candidate head:

- `testDebugUnitTest`
- `assembleDebug`
- `assembleRelease` with R8
- debug APK artifact upload
- release APK artifact upload

A candidate is not ready for device testing if any gate is red.

## Device coverage

Use the broadest available spread:

- API 26 or nearest available device/emulator, covering the minimum supported SDK;
- API 29 or 30, covering pre-modern clipboard behavior;
- API 33 or newer, covering Android clipboard access notifications and previews;
- API 36 when available, covering the current target platform;
- one physical AICore-capable device for existing on-device AI regression checks.

## Installation and migration

1. Fresh install the candidate and confirm the keyboard enables, selects, and renders.
2. Upgrade an installation containing Room v7 data:
   - saved snippets;
   - quick shortcut templates;
   - custom slash commands;
   - learned vocabulary and corrections.
3. Confirm all pre-upgrade data remains intact after Room v8 opens.
4. Confirm clipboard history begins empty and disabled after upgrade.
5. Force-stop and relaunch both the companion app and IME; confirm settings and retained clips survive process death.

## Clipboard history privacy

1. Confirm the clipboard-history bar is absent before opt-in. Open Setup Guide → Clipboard History (optional) → Open Clipboard History; confirm the manager is reachable while history is disabled.
2. Focus a password field and confirm:
   - the clipboard-history bar is absent;
   - no automatic capture occurs;
   - Capture cannot be invoked from the IME.
3. Enable history through the companion manager, return to an ordinary text field, and test manual capture rejection for:
   - `password: hunter2`;
   - a verification or one-time code;
   - a Luhn-valid payment-card number;
   - CVV/CVC-shaped content;
   - a bearer token or JWT;
   - a private-key header;
   - an SSN-shaped identifier;
   - a contextual seed/recovery phrase;
   - text longer than 4,000 characters.
4. Confirm rejection messages never repeat the sensitive clipboard value.
5. Confirm no rejected item appears after app restart.
6. With network inspection available, confirm capture, history viewing, pinning, deletion, and clearing produce no network request.

## Capture lifecycle

1. Enable history and place ordinary text on the system clipboard.
2. Reopen the keyboard in a normal field; confirm foreground capture adds the clip.
3. Keep the keyboard open, change the clipboard, tap **Capture**, and confirm the new clip appears.
4. Copy the same normalized text again and confirm it collapses into one row rather than duplicating.
5. Pause history, change the system clipboard, reopen the keyboard, and confirm no new clip appears.
6. Resume history and confirm foreground capture resumes.
7. Disable history and confirm:
   - new capture stops;
   - existing history remains visible in the manager;
   - re-enabling does not duplicate unchanged content.

## Retention and limits

1. Verify the manager offers 1, 7, and 30-day retention choices.
2. Seed or age unpinned rows and confirm expired rows are removed when pruning runs.
3. Confirm pinned rows survive ordinary retention pruning.
4. Add more than 20 distinct unpinned clips and confirm only the 20 newest remain.
5. Pin 10 clips and confirm the eleventh pin is rejected with a clear message.
6. Confirm duplicate capture refreshes recency without removing pin state.

## Keyboard behavior

1. Tap a history chip and confirm it inserts exactly once at the cursor.
2. Confirm tapping a history chip does not start an AI request.
3. Confirm multi-line content preserves line breaks when inserted.
4. Confirm pinned clips appear before unpinned clips.
5. Confirm the bar remains usable in light and dark keyboard themes.
6. Confirm the bar does not cover or break:
   - Trust Prism;
   - Reply Completeness Coach;
   - Snippet Vault recall;
   - command palette;
   - AI result shelf;
   - number row and normal key rows.

## Companion manager

1. Open **Manage** from the keyboard and confirm the activity is reachable only through the app/IME flow.
2. Toggle enable and pause; close the manager and confirm the IME refreshes on return.
3. Capture current clipboard from the manager.
4. Copy a history item back to the system clipboard.
5. Pin and unpin an item.
6. Delete one item and confirm no confirmation is required for the single-row action.
7. Tap **Clear now**, cancel, and confirm nothing changes.
8. Tap **Clear now** again, confirm, and verify pinned and unpinned history are both removed.

## Snippet Vault regression

1. Create, edit, tag, alias, and delete a saved snippet.
2. Recall snippets with `/v` and `/find`.
3. Confirm result display alone does not count as use.
4. Confirm tapping a saved snippet inserts it and records use.
5. Confirm `/v command :: draft` still runs only after the result tap.
6. Confirm ordinary custom slash commands and quick abbreviation expansion remain unchanged.

## Core keyboard regression

Smoke-test the existing high-risk paths:

- enable/select keyboard flow;
- standard typing, delete, shift, caps lock, space, and Enter/action keys;
- swipe typing;
- cursor movement;
- clipboard intelligence actions;
- grammar, rewrite, summarize, translate, compose, continue, tone, and replies;
- offline/on-device routing where supported;
- Send Guard and Reply Completeness ordering;
- AI apply undo and auto-correction undo;
- per-app persona restoration;
- Keyboard Passport export/import;
- learning pause and secure-field suppression.

## Exit criteria

The release candidate may advance when:

- all automated gates are green on the final SHA;
- Room v7→v8 upgrade succeeds without data loss;
- no secure-field capture is observed;
- no rejected sensitive clip persists;
- no P0 or P1 regression remains open;
- clipboard history has been exercised on at least two Android API levels, including one API 33+ device;
- remaining lower-severity defects have reproduction steps and an explicit ship/fix decision.

## PR #102 follow-up: physical-device sign-off (not yet executed)

Record final SHA, artifact/run ID, device, Android API, navigation mode, display/font
scale, host editor, expected/actual result and screenshot/log reference for each row.
Start on the Pixel 10 Pro, then cover the Android eras listed above. Test native
EditText, a Compose text field, and a WebView/browser editor. CI cannot sign off
physical clipboard access, OEM IME window dimensions, or real insets.

| Scenario | Procedure | Pass condition | Result |
| --- | --- | --- | --- |
| Rotation | Type with number row on; rotate both ways without closing IME | All keys and editor reachable, no text loss, row hides/restores | NOT RUN |
| Split-screen | Resize both app positions with IME open; cross 479/480dp height | Metrics refresh, no clipping; record actual IME/configuration bounds | NOT RUN |
| Maximum chrome | Populate history, recall Vault, open expanded AI result and applicable banners; increase font scale | Keys and host caret usable; every panel action reachable | NOT RUN |
| Live tokens | Expand clipboard/date/cursor through space, gesture and Vault | Exact single insertion, correct caret and current date | NOT RUN |
| Clipboard variants | Multiline/emoji, empty, unavailable, and non-text clipboard | No crash or duplicate insertion; empty fallback is understood | NOT RUN |
| Clipboard privacy | Escaped token, password and numeric-password field, hide/reopen IME | No clipboard read for escaped/secure/hidden use | NOT RUN |
| Ordinary OTP input | Exercise host OTP fields lacking password flags | Document classification; do not assume every OTP editor is detectable | NOT RUN |
| Undo | Start/middle/end marker, emoji, selected text, surrounding text, then immediate backspace | Space/gesture undo restores original and preserves neighbors | NOT RUN |
| Stale undo | Move caret, alter suffix, or change editor before backspace | Old transaction does not undo unrelated content | NOT RUN |
| Existing snippets | Upgrade with nested JSON/code, unknown tokens and literal braces | No closing braces lost; data remains intact | NOT RUN |
| Settings route | Fresh disabled state → manager → enable → disable → reopen → clear | Manager always reachable and bar/settings refresh correctly | NOT RUN |

The core compact arithmetic is not the total IME height. Automated layout tests now
render the service's shared view tree and exercise configuration changes, but do not
establish maximum-chrome or OEM split-screen behavior. Do not approve beta until
these rows and the existing migration/privacy gates pass on the final SHA.

Token escape syntax: use a complete `{{token}}` block for literal `{token}`.
Unpaired closing braces remain literal, preserving nested JSON/code. Recognized
single-brace tokens remain dynamic, including when intentionally embedded in JSON.

CodeQL now builds debug and release sequentially to avoid the reported shared
Room schema export overlap in that workflow. This is a workflow mitigation, not
a repository-wide per-variant schema migration; other concurrent build entry points
still need investigation if the failure recurs.
