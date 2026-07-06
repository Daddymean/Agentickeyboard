# Roadmap

Prioritized backlog for Agentic Keyboard. Work top-down, one item (or one
paired item) per session/PR. Update this file in the same PR that ships an
item: move it to **Shipped** with the PR number.

## Next up

### 1. Keyboard theming / dark mode
`ui/theme/KeyboardTheme.kt` already defines a light/dark palette behind a
CompositionLocal, but `AgenticKeyboardLayout` hardcodes `Color(0xFF...)`
values throughout (keyboard background, shelf, chips, panels), so the
keyboard ignores system dark mode. Mechanical refactor: route every color
through the theme, then honor `isSystemInDarkTheme()`. Note: the shelf's
`ExpandableResult` helper and per-panel label colors are part of this sweep.

### 2. Selection-scope indicator
When a text selection is active, the AI actions silently operate on it
(shipped in PR #12). Add a small badge on the AI action row ("acting on
selection") so the behavior is discoverable instead of surprising.

## Later / unscheduled

- Word-level diff highlighting in the expanded result preview — the current
  compare shows the whole original struck through ("Was: …"); highlighting
  only the changed spans would make short edits scannable.
- Expandable preview for the Explanation panel (the one result panel still
  capped to a fixed-height shelf).
- Multi-step undo: the AI-apply undo holds a single pending entry; an undo
  chip in the shelf could offer a small history instead of backspace-only.
- Clipboard history (multiple recent clips, not just the current one).
- Long-press accent/symbol popups on keys.
- Multi-variant results: generate 2–3 rewrite candidates and let the user pick.
- Streaming for Continue so the suggestion appears as it generates.
- Instrumented/screenshot tests for the keyboard layout (CI currently only
  builds the APK and runs pure-JVM tests).

## Shipped

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
