# Roadmap

Prioritized backlog for Agentic Keyboard. Work top-down, one item (or one
paired item) per session/PR. Update this file in the same PR that ships an
item: move it to **Shipped** with the PR number.

## Next up

### 1. Selection-scope indicator
When a text selection is active, the AI actions silently operate on it
(shipped in PR #12). Add a small badge on the AI action row ("acting on
selection") so the behavior is discoverable instead of surprising.

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

## Shipped

- **branch `claude/roadmap-review-updates-4cjt0i`** — keyboard theming / dark
  mode: `AgenticKeyboardLayout` now provides `LocalKeyboardColors` off
  `isSystemInDarkTheme()` and routes every surface, key, chip, popup and label
  through the palette (no more hardcoded `Color(0xFF…)` in the layout). Extended
  `KeyboardColors` with `error`/`onError` and per-feature result-label colours
  so the shelf's colour coding survives the dark switch.
- **branch `claude/roadmap-review-updates-4cjt0i`** — undo for applied AI
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
