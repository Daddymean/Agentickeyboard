# Roadmap

Prioritized backlog for Agentic Keyboard. Work top-down, one item (or one
paired item) per session/PR. Update this file in the same PR that ships an
item: move it to **Shipped** with the PR number.

## Next up

### 1. On-device AI, Phase 1 — ML Kit GenAI (Gemini Nano via AICore)

**Next-session kickoff prompt:** *"Implement ROADMAP item 1 (On-device AI
Phase 1) exactly as specified there. One focused PR on the designated branch;
verify with a workflow_dispatch CI run before reporting."*

Goal: when offline mode is on (or the network is down) and the device has
AICore, route **Fix Grammar**, **Rewrite (+ iterate chips)**, and
**Summarize** through on-device Gemini Nano instead of the canned heuristics.
Heuristics remain the final fallback; cloud path unchanged. Plan researched
and verified 2026-07-07:

- **Dependencies (real — verified on Google's Maven, unlike the
  `com.google.mlkit:genai:16.0.0` hallucination on `refactor/cleanup-v1`):**
  `com.google.mlkit:genai-proofreading`, `genai-rewriting`,
  `genai-summarization` (resolve current versions at implementation time).
  The freeform `genai-prompt` API (1.0.0-beta2, alpha) is **Phase 2** —
  replies/compose/continue/tone — do not pull it into Phase 1.
- **No manifest changes.** ML Kit binds AICore itself; the INTERNET-only
  permission rule stands. Do NOT add `com.google.android.gms.permission.AI_CORE`.
- **New abstraction** `util/OnDeviceAi.kt`: small interface
  (`proofread(text)`, `rewrite(text, tone)`, `summarize(text)`, plus a
  `StateFlow` availability status) with an ML Kit-backed implementation and a
  fake for tests. Availability is async: check feature status →
  AVAILABLE / DOWNLOADABLE (trigger download, report progress) / UNSUPPORTED.
- **Routing** lives where the single offline-fallback module already sits in
  `GeminiManager`: offline path tries `OnDeviceAi` when AVAILABLE, else the
  existing heuristics. Keep the routing decision pure-JVM testable (inject
  availability + provider; add routing unit tests with the fake).
- **UI**: one Style Hub row — "On-device AI: Available / Downloading / Not
  supported on this device". Iterate chips map to Rewriting preset tones
  (SHORTEN / FRIENDLY / PROFESSIONAL, ...) where they exist.
- **Constraints**: English-first API support (Translate stays cloud-only);
  keep the strip-fences/validate-output habits; failures degrade silently to
  heuristics — never surface an error for a missing model. Keyboard process
  stays lean — ML Kit/AICore does inference out-of-process, so no model
  loading in the IME.
- **Validation**: push branch, `workflow_dispatch` on android-build.yml; the
  R8 release step must also stay green (add keep/dontwarn rules if ML Kit's
  transitives need them). Runtime behavior needs a supported device
  (Pixel 9/10, Galaxy S24+, recent flagships) — flag that for manual testing.
- **Explicitly out of scope**: Gemma/LiteRT-LM tier for non-AICore devices
  (Phase 3, only if mid-range coverage becomes a goal; host outside the IME
  process, opt-in download).

Context: `refactor/cleanup-v1`'s AICore attempt was verified non-building
(CI run #35: unresolvable dependency; undefined `AICore`/`Prompts.forTask`
symbols; reaches into private ViewModel members). Do not merge or reuse its
AI files; its extracted-component file boundaries are an acceptable map for a
future monolith split, nothing more.

### 2. In-keyboard theme override
Dark mode now follows the system setting. Add an explicit Light/Dark/System
choice (a `KeyboardSettings` entry + a control in the Style Hub) so users can
pin the keyboard's theme independent of the OS, and have the layout read that
override instead of only `isSystemInDarkTheme()`.

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

## Shipped

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
