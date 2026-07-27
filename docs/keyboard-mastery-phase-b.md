# Keyboard Mastery Phase B

Phase B turns the local progression foundation into a feature-discovery loop without rewarding screen time, message sending, or Android input-method selection.

## Daily missions

The companion app shows up to three missions selected deterministically from underused mastery events. Selection favors features with little or no lifetime use and then spreads the visible deck across different mastery paths where possible.

Missions:

- are optional and dismissible without losing XP or streaks;
- infer progress from aggregate event counters already produced by the keyboard;
- reset their dismissals on the next epoch day;
- award no separate mission bonus, so repeating an action cannot bypass Phase A caps;
- never target sending a message, enabling the IME, or selecting the default keyboard.

A completed mission uses a small Compose visibility transition. Compose follows the system animation-duration setting, and completion remains understandable as text when animation is disabled.

## Rolling weekly report

`MasteryState` now retains at most 28 aggregate-only daily snapshots. Each snapshot contains an epoch day and counts keyed by `MasteryEvent`. This is enough to compute:

- current rolling seven-day actions;
- previous seven-day actions;
- active-day counts;
- the most-used mastery path;
- the best retained day;
- conservative estimates for keystrokes and time saved.

The estimate uses documented per-event constants and 200 keystrokes per minute. It is presented as an estimate, not a measured productivity claim.

## Persistence migration

`MasteryStateCodec` advances from version 1 to version 2. Version 1 data is decoded without losing XP, streaks, achievements, lifetime counts, or current-day counters. The current Phase A day becomes the first history snapshot during migration.

Malformed, unknown-version, or partially damaged payloads still fail closed to a fresh local state.

## Privacy boundary

No mission or report model accepts raw text, app names, recipients, contact data, field values, or system default-keyboard state. Secure fields remain blocked at the existing event-recording boundary, so they cannot enter daily history.
