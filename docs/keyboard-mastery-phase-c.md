# Keyboard Mastery Phase C

Phase C adds an optional visual keepsake to the companion app. It does not alter the keyboard, AI routing, progression awards, or Android input-method setup.

## Constellation stages

The Lumina constellation grows deterministically from existing total XP:

- Spark: 4 stars
- Orbit: 6 stars
- Cluster: 8 stars
- Constellation: 10 stars
- Aurora: 12 stars

The drawing is static and changes only when progression or the selected aura changes. There is no continuous pulsing, flashing, particle loop, or time-pressure animation.

## Cosmetic auras

Auras are optional visual treatments with independent requirements:

- Starlight is always available.
- Ember unlocks from total XP.
- Prism rewards balanced progress across all four mastery paths.
- Aurora rewards collected achievements.
- Comet rewards a retained personal-best streak.

A locked, removed, or unknown selected aura safely falls back to Starlight. Unlocks never gate keyboard features.

## Persistence

Two SharedPreferences values are stored separately from progression state:

- whether the constellation is visible
- the selected aura ID

Resetting mastery may make an aura unavailable, but it cannot corrupt progression data or block the companion card. The resolver displays the default aura until the requirement is met again.

## Privacy and policy boundaries

- The feature uses only aggregate `MasteryState` values already stored on device.
- It stores no raw text, app identity, recipients, contacts, or field values.
- It renders only inside the companion app, never inside the IME or secure fields.
- It grants no XP, product capability, discount, or prize.
- Enabling Lumina or selecting it as Android's default input method is not an unlock requirement.
