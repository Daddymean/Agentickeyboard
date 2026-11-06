# Phone smoke checklist: #190, #191, #192 (+ #045 gate)

Run on Keith's phone after installing a debug build over the existing debug build
(don't uninstall: that wipes twin data). About 10 minutes. Tick each line.

## Before you start
- [ ] Settings → Apps → Agentic Keyboard shows the new version; twin data still there (Twin → Memories lists old items).
- [ ] Quick-ask bubble switch in Twin settings is **off** (it must be off after an upgrade).
- [ ] Journal → handwriting recognition shows as not approved (no model download has happened).

## #190 privacy filter (never learn)
- [ ] Type in a password field (any app login): twin capture count doesn't change.
- [ ] Type in an incognito Chrome tab: no capture.
- [ ] Type a 6-digit code into an SMS/OTP field and a card number into a payment field: no capture.
- [ ] Add your bank app's package to "Never learn in these apps", save, type there: no capture. Remove it again.
- [ ] Type a normal sentence in Notes/Messages: capture count goes up by one.

## #191 risk levels + approval gate (#045, single-run nonce)
- [ ] Twin → Export: tapping "Choose where to save" asks every time ("You'll be asked every time"). Cancel → nothing saved.
- [ ] Allow → pick Downloads → export saves and verifies. Do it again: it asks again (each Allow is one run only; a leftover approval cannot unlock a second export).
- [ ] Dead-man → "Save a new release archive": always asks. Cancel → no file.
- [ ] Capsule → Seal: first time asks once with "Don't ask again". Tick it, allow → sealed.
- [ ] Twin settings lists "Ask again before: Seal a time capsule". Tap it → list is empty again.
- [ ] Capsule → Delete: asks every time and says it can't be undone. Cancel → still there.
- [ ] Memories → Approve: asks once (WRITE).

## #192 quick-ask bubble
- [ ] With the switch off, no bubble appears anywhere.
- [ ] Turn it on; grant "Display over other apps" when asked. Open a text field: bubble shows.
- [ ] Move to a password field: bubble hides. Incognito tab: hidden. Twin's own screens: hidden.
- [ ] Tap the bubble: opens the cited-answer screen, nothing else.
- [ ] Turn the switch off: bubble gone immediately.

## Handwriting
- [ ] Journal → "Write it by hand" → "Read my handwriting" asks for approval first and shows the Google model disclosure. Decline → no download, handwriting still saved.

## Report
Reply on the board thread with any unticked line, the step, and what you saw.
Automated coverage: `TwinUpgradeDefaultsTest` (bubble off after upgrade, handwriting
off until enabled), `TwinPrivacyFilterTest`, `TwinActionPolicyTest`, `TwinApprovalsTest`,
`TwinBubblePolicyTest`.
