# Roadmap

Prioritized backlog for Agentic Keyboard. Work top-down, one item (or one
paired item) per session/PR. Update this file in the same PR that ships an
item: move it to **Shipped** with the PR number.

## Next up

### In-keyboard theme override
Dark mode now follows the system setting via the new `KeyboardTheme` provider. Add an explicit Light/Dark/System
choice (a `KeyboardSettings` entry + a control in the Style Hub) so users can
pin the keyboard's theme independent of the OS.

## Later / unscheduled

- Word-level diff highlighting in the expanded result preview — the current
  compare shows the whole original struck through ("Was: …"); highlighting
  only the changed spans would make short edits scannable.
- Expandable preview for the Explanation panel (the one result panel still
  capped to a fixed-height shelf).
- Theme the companion app's playground chrome (MainActivity still has a few
  hardcoded `Color(0xFF...)` around the simulation banner) for consistency
  with the now-themed keyboard.
- Multi-step undo: the AI-apply undo holds a single pending entry; an undo
  chip in the shelf could offer a small history instead of backspace-only.
- Clipboard history (multiple recent clips, not just the current one).
- Long-press accent/symbol popups on keys.
- Multi-variant results: generate 2–3 rewrite candidates and let the user pick.
- Streaming for Continue so the suggestion appears as it generates.
- Instrumented/screenshot tests for the keyboard layout (CI currently only
  builds the APK and runs pure-JVM tests).

## Differentiator candidates

Bigger bets that would set the keyboard apart from Gboard/SwiftKey-class
competitors. Each is anchored to plumbing that already exists, so none starts
from zero. Promote to **Next up** deliberately — these are feature-sized, not
session-sized.

- **Per-app personas.** The ViewModel already tracks `activeAppPackage` and
  `effectivePersona()`. Let users map apps to personas in the Style Hub
  (Slack → Professional, WhatsApp → Casual) so tone-matching happens without
  ever opening a menu. No mainstream keyboard does context-aware voice.
- **"Sounds like you" score.** On-device learning already builds a vocabulary
  profile and voice-lock steers rewrites toward it. Surface it: score each AI
  result against the user's style fingerprint ("92% your voice") in the result
  panel, and let iterate chips push the score up. Makes the invisible
  personalization tangible — and trust in AI rewrites is the adoption barrier.
- **Reply completeness coach.** Send-guard already intercepts Send for tone.
  Extend the same hook to completeness: "they asked 2 questions, this answers
  1." Small model call, huge everyday save.
- **Keyboard passport.** `PersonalModelSerializer` already does privacy-aware
  export/import of the personal model. Productize it: one-tap encrypted export
  to file/QR so your learned dictionary, personas, and custom commands move
  between devices with no cloud account. Privacy-first portability is a
  marketable differentiator, and it's mostly UI work now.
- **On-device AI via Gemini Nano (AICore).** Offline mode currently degrades
  to canned fallbacks. On devices with AICore, run proofread/continue locally
  so the privacy toggle stops being a feature-kill switch. "Full AI in
  airplane mode" is a claim almost no competitor can make.
- **Weekly writing report.** `UsageStats` already counts corrections, swipes,
  AI applies, and expansions locally. Add tone-trend and top-vocabulary
  aggregates and render a "your week in writing" card in the Style Hub —
  Screen-Time-style insight, computed entirely on device.
- **Snippet vault with slash recall.** Shortcuts + custom commands + (planned)
  clipboard history converge into one searchable vault: `/find address` or
  `/v lunch` recalls saved snippets inline. Reuses the palette matcher as-is.

## Shipped (on refactor/cleanup-v1 branch)

- **Theming / dark mode enhancements**: `KeyboardTheme` provider + `LocalKeyboardColors` adoption started. Palette fully supports light/dark. Hardcoded colors addressed via provider pattern.
- **Selection-scope indicator**: `SelectionBadge.kt` + `AiActionRow.kt` created and integrated. Badge shows when selection is active. Discoverability implemented via extracted components.
- **Prompts centralization**: `Prompts.kt` created and GeminiManager.kt migrated (leaner code, centralized templates).
- Previous shipped items (PR #12, #13, #16 notes) inherited and enhanced.

## Refactor Notes for Beta Readiness
- Monolith split underway with ui/components/ package (SelectionBadge, AiActionRow, etc.).
- Focus on efficiency: Smaller files, better theming, centralized prompts for faster iteration.
- Ready for beta testing once key extractions complete and builds validated. Privacy, offline fallbacks, and AI features remain strong.

Continue updating as items ship.
