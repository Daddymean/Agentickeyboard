# Roadmap

Prioritized backlog for Agentic Keyboard. Work top-down, one item (or one
paired item) per session/PR. Update this file in the same PR that ships an
item: move it to **Shipped** with the PR number.

## Next up

- **Reply completeness coach** — reuse Send Guard's editor-action seam to
  notice when an incoming message asked multiple questions but the current
  draft appears to answer only some of them. Keep it advisory and reversible,
  prefer local/on-device analysis where available, and never send, rewrite, or
  block a message automatically.

## Later / unscheduled

- On-device AI, Phase 3 — Gemma/LiteRT-LM tier for non-AICore devices, only if
  mid-range coverage becomes a goal; host outside the IME process with an
  opt-in download. (Phases 1 and 2 shipped in PR #22 and PR #24.)
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

- **Keyboard passport.** `PersonalModelSerializer` already does privacy-aware
  export/import of the personal model. Productize it: one-tap encrypted export
  to file/QR so your learned dictionary, personas, and custom commands move
  between devices with no cloud account. Privacy-first portability is a
  marketable differentiator, and it's mostly UI work now.
- **On-device AI via Gemini Nano (AICore).** Offline mode currently degrades
  to canned fallbacks. On devices with AICore, run proofread/continue locally
  so the privacy toggle stops being a feature-kill switch. "Full AI in
  airplane mode" is a claim almost no competitor can make.
- **Snippet vault with slash recall.** Shortcuts + custom commands + (planned)
  clipboard history converge into one searchable vault: `/find address` or
  `/v lunch` recalls saved snippets inline. Reuses the palette matcher as-is.

## Shipped

- **PR #61** — local “Sounds like you” scoring: added an explainable,
  in-memory style fingerprint from learned vocabulary and bounded local writing
  samples; eligible grammar, rewrite, compose, and continuation results show a
  confidence-aware match percentage, a derived signal, and refinement delta.
  Insufficient evidence produces no score. Candidate text, fingerprints, and
  score history are never persisted or uploaded; the score never gates
  features, progression, or Android input-method selection.

- **PR #60** — Keyboard Mastery, Phase C: added a companion-app-only Lumina
  constellation with five deterministic growth stages, five optional visual
  auras, testable unlock rules, safe fallback after resets, persistent hide and
  aura preferences, and a static accessibility-safe star map. Cosmetics use
  existing local aggregates, grant no capabilities or bonus XP, never render
  in the IME, and do not depend on Android input-method selection.

- **PR #59** — Keyboard Mastery, Phase B: added a bounded 28-day aggregate
  history with automatic Phase A persistence migration, three optional daily
  missions selected from underused features, penalty-free dismissal, diverse
  mastery-path recommendations, and a rolling seven-day report with prior-week
  comparison, active days, personal bests, and conservative estimates for
  keystrokes and time saved. The Style Hub shows mission progress and a compact
  report; no raw text or Android default-input state enters either model.

- **PR #57** — Keyboard Mastery, Phase A: added a fully local progression
  engine with Flow, Clarity, Voice, and Trust paths; per-event and per-path
  daily XP caps; gentle streaks with grace days and retained personal bests;
  achievement IDs; defensive SharedPreferences serialization; and a Style Hub
  progress card with pause and reset controls. Existing aggregate events feed
  mastery without storing typed text, and secure fields produce no progress.
  Core keyboard features and Android input-method selection remain ungated.

- **PR #48** — integrated foreground AI sessions: `KeyboardViewModel` now binds
  its existing `aiPanelState` contract to `AiSessionController`, delegates
  request replacement, cancellation, loading cleanup, dismissal, and
  regeneration, and removes its duplicate `aiJob`, stored regeneration callback,
  and coroutine cancellation implementation. Action-specific prompts,
  personalization, offline routing, learning, and repository writes remain in
  the ViewModel; the public UI contract is unchanged.

- **PR #47** — introduced the tested `AiSessionController` seam with ownership
  of foreground request cancellation, loading cleanup, panel publication,
  dismissal, and regeneration. Coroutine tests cover cancellation, empty and
  failed completion, completed-result persistence, dismissal, and latest-action
  regeneration.

- **PR #45** — unified AI panel state: the ViewModel now exposes one sealed
  `AiPanelState` instead of parallel nullable flows for loading, reply intent,
  replies, grammar, tone, summary, translation, rewrite, compose, explanation,
  and continuation. Comparison source text and refinement eligibility travel
  with the active result, making contradictory panel combinations impossible
  and reducing manual dismissal bookkeeping. The keyboard and companion
  playground now collect the same single state; Send Guard and the background
  proofread hint remain independent safeguards.

- **PR #29** — AI response-cache hardening: cloud actions now use typed,
  ten-minute in-memory LRU caches with opaque SHA-256 keys built from the model
  and every prompt-affecting input. This fixes summary and translation cache
  keys that previously omitted personalization context, prevents raw typed text
  from being retained in map keys, and removes the shared `Any` cache plus its
  unchecked casts. Tests cover key opacity, context/language separation,
  expiration, and LRU eviction.

- **PR #28** — Trust Prism privacy status: a compact banner above the IME now
  makes the active data path visible while typing. The tested state model gives
  secure fields highest priority, then offline/on-device mode, then normal
  cloud operation with PR #27's redaction guard; a distinct warning state exists
  if cloud redaction is ever disabled. The banner also surfaces when local
  personalization learning is paused. AI routing and request behavior are
  unchanged.

- **PR #27** — always-on cloud request redaction: a pure-JVM sanitizer now
  replaces credential-shaped secrets, emails, card-like numbers, SSNs, long
  numeric identifiers, phone numbers, IPv4 addresses, and URLs in the final
  serialized Gemini request immediately before OkHttp transmits it. The shared
  interceptor protects every current and future cloud AI action through one
  boundary; ordinary writing remains unchanged and unit tests cover sensitive
  values plus serialized JSON request bodies. A visible Trust Prism UI is the
  follow-up once the boundary implementation is proven by CI.

- **PR #26** — per-app personas: the keyboard already remembered the persona
  last used in each app (`onEditorStarted` restore + `setUserPersonaPreference`
  save); this surfaces those mappings in the Style Hub "Per-app personas" card
  where each app shows its persona in a dropdown (change) with a remove button.
  Added `AppPersonaDao.getAllFlow`/`delete`, a repository flow +
  `deleteAppPersona`, an `appLabel` column (DB v5→v6 + `MIGRATION_5_6`) resolved
  by the IME so the companion app shows "Slack" not "com.Slack" without
  package-query permissions, and `AppPersonas.friendlyName` (pure-JVM, tested)
  for the display fallback. No new permissions or manifest changes.

- **PR #24** — on-device AI, Phase 2 (freeform `genai-prompt` / Gemini Nano via
  AICore): offline **replies**, **compose**, **continue**, and **tone** now run
  on-device when the prompt feature is available, degrading silently to the
  existing heuristics otherwise (cloud path unchanged). Extended `OnDeviceAi`
  with a separate `promptStatus` gate + `generate(prompt)` so the freeform model
  and the Phase 1 task features never disable each other; added
  `offlineReplies`/`offlineCompose`/`offlineContinue`/`offlineTone` routing in
  `GeminiManager` (also the cloud-error fallbacks), Nano-specific prompt
  templates, and pure-JVM routing/parsing tests. Bumped `genai-common` to beta3
  (pulled by `genai-prompt` beta2); no manifest changes. Runtime behavior still
  needs manual testing on an AICore device.

- **PR #23** — in-keyboard theme override: a System/Light/Dark chip row in the
  Style Hub (`KeyboardSettings.themeOverride`, synced across the companion app
  and IME processes like every other setting) now pins the keyboard palette;
  `AgenticKeyboardLayout`'s root `KeyboardTheme` reads the override and only
  defers to `isSystemInDarkTheme()` on "System".

- **PR #22** — on-device AI, Phase 1 (ML Kit GenAI / Gemini Nano via AICore):
  offline **Fix Grammar**, **Rewrite** (+ iterate chips, mapped onto the
  Rewriting presets SHORTEN/ELABORATE/FRIENDLY/PROFESSIONAL where they exist)
  and **Summarize** now run on-device when AICore is available, degrading
  silently to the canned heuristics otherwise (cloud path unchanged). New
  `util/OnDeviceAi.kt` abstraction + `MlKitOnDeviceAi`, routing centralized in
  `GeminiManager` (`offlineGrammarFix`/`offlineSummary`/`offlineRewrite`, also
  the cloud-error fallbacks), pure-JVM routing/tone-mapping tests with a fake,
  and a Style Hub availability row. Raised `minSdk` 24→26 (the ML Kit GenAI
  AARs declare 26; no manifest changes). Runtime behavior still needs manual
  testing on an AICore device (Pixel 9/10, Galaxy S24+ class).

- **(this branch)** — selection-scope indicator: the IME service mirrors the
  editor's selection state into the ViewModel and the AI action row shows an
  "Acting on selection" badge while a selection is active. Also ported the
  good parts of `refactor/cleanup-v1`: prompt templates extracted to
  `network/Prompts.kt` (GeminiManager keeps orchestration only) and a
  `KeyboardTheme` root provider for the palette. Release R8 minification was
  switched back off pending keep rules + a verified release build.

- **PR #16** — keyboard theming / dark
  mode: `AgenticKeyboardLayout` now provides `LocalKeyboardColors` off
  `isSystemInDarkTheme()` and routes every surface, key, chip, popup and label
  through the palette (no more hardcoded `Color(0xFF…)` in the layout). Extended
  `KeyboardColors` with `error`/`onError` and per-feature result-label colours
  so the shelf's colour coding survives the dark switch.
- **PR #16** — undo for applied AI
  results (⌫ right after Apply/Append restores the replaced draft/selection,
  via `AiApplyUndo` mirroring the smart-space undo) + expandable result
  preview (tap a result to grow the shelf, read the full output, and see the
  original it replaces; grammar uses its own `original`, summary/translate/
  rewrite use the new `aiResultSource` flow).
- **PR #13** — user-defined palette commands: `CustomCommand` Room entity +
  Custom Commands editor in MainActivity, merged into the slash palette
  after the built-ins (was item 4 here; the list predated its landing).

- **PR #12** — high-impact cluster: selection-scoped AI actions,
  intent-directed replies, slash command palette, voice-lock setting,
  regenerate button + iterate chips (Shorter/Longer/Warmer/Firmer/More
  formal), plus pure-JVM tests for command parsing and intent mapping.
