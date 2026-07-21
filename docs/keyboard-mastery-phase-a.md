# Keyboard Mastery, Phase A

Keyboard Mastery is an optional, local-only progression layer that rewards useful keyboard habits rather than app-opening time or Android setup changes.

## Paths

- **Flow**: swipe words and shortcut expansions.
- **Clarity**: accepted corrections and general AI result applications.
- **Voice**: translations, voice-locked rewrites, and refinement actions.
- **Trust**: AI results applied while offline mode is active.

## Privacy boundary

Mastery events are enum values with counters. They contain no typed text, app package, recipient, contact, field value, or message metadata. `KeyboardViewModel` passes the secure-field flag into the progression engine, and the engine refuses to alter state when that flag is true.

The system works entirely offline and stores one compact, defensive state string in the existing private `SharedPreferences` file.

## Anti-farming rules

- Each event type awards XP at most ten times per day.
- Each path awards at most 40 XP per day.
- Aggregate event counts may continue increasing for personal records, but XP stops at the caps.
- Core keyboard behavior is never locked behind a level.

## Gentle streaks

One meaningful event starts or advances the daily rhythm. A single missed day can consume a grace day instead of breaking the rhythm. Longer gaps reset only the current streak; the personal best remains. Every seven-day milestone replenishes one grace day up to a maximum of two.

## Controls

The Style Hub card includes:

- per-path level and XP progress
- current rhythm and personal best
- achievement count and recent achievement names
- a pause switch that leaves every keyboard feature available
- a reset action that clears only mastery XP, streaks, and achievements

Enabling Lumina or selecting it as the Android input method never awards XP, unlocks features, or changes mastery progress.
