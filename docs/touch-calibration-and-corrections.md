# Faster corrections and touch calibration

The main keyboard now includes a comma beside the period. The suggestion shelf
uses an indexed on-device dictionary for single-letter substitutions, omissions,
extra letters and adjacent transpositions, with learned typo rules first. Generic
dictionary guesses are offered for explicit acceptance; existing learned rules
still apply on space and keep their backspace undo behavior.

Background proofreading remains opt-in and starts after 700 ms without a text
change (previously 2500 ms). An already available hint is reused by Fix Grammar.
Typing invalidates old hints, switching editors cancels work, and cancelled cloud
proofreads do not start offline inference. Network/model inference time still
varies; this change reduces the scheduling delay, not a measured end-to-end SLA.

Choose **Train touch** above the letters, then tap the displayed target through
three rounds of all 26 letters (78 taps). Letter taps are consumed by the trainer
instead of entering text. Swipe typing and accent popups are disabled during the
trainer. Complete all rounds to save; cancelling discards the session. **Reset
touch** clears the profile. Training is unavailable while learning is paused or
in password/incognito fields. Sensitive fields also bypass calibrated decoding.

Only bounded per-letter mean offsets and sample counts persist in private app
settings. No typed words or individual tap history are saved by calibration.
Offsets scale with current key bounds; edge taps may be reassigned to an adjacent
letter, while central taps stay unchanged. Long-press variants, swipe decoding,
punctuation and accessibility clicks retain their existing behavior.

## Validation

CI must compile debug/release and run LocalSpellingTest, TouchCalibrationTest and
KeyboardLayoutWindowSizeTest. Local Android builds are prohibited by CLAUDE.md
because this environment has no SDK.

Physical Pixel validation remains not_run:
- Verify comma, period, space and Enter fit in portrait, landscape and split screen.
- Type `shoulf`, `suggestins`, `teh`, and `keyboaard`; verify suitable suggestions
  and replacement of only the current word. Test correct words and undo.
- Compare proofread hint arrival with the prior APK on identical drafts/network;
  verify stale results cannot appear after typing or switching editors.
- Complete calibration, test edge taps, restart the IME, and verify persistence.
  Cancel an incomplete retraining and verify the old profile remains. Reset it.
- Verify password/incognito fields, paused learning, swipe typing, accent holds,
  shift/caps lock and accessibility activation.
