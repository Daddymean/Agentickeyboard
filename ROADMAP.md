# Roadmap

Prioritized backlog for Agentic Keyboard. Work top-down, one item (or one
paired item) per session/PR. Update this file in the same PR that ships an
item: move it to **Shipped** with the PR number.

## Next up

### 1. Undo for applied AI results (highest impact)
`replaceActiveText` destructively replaces the draft/selection when the user
taps Apply, with no way back. Extend the existing backspace-undo pattern
(`peekPendingUndo` / `AutoCorrectionUndo` used by smart-space) to AI applies:
pressing ⌫ right after an Apply restores the original text. Removes the
biggest trust barrier to using the AI actions. Pairs with item 2 (same code
path) — ship them together.

### 2. Result preview / diff before applying
Every AI result panel renders `maxLines = 1`, so users apply multi-sentence
rewrites they cannot read. Add an expandable preview (tap the result text to
expand the shelf) and ideally an original-vs-result highlight so the user
sees what changes before committing.

### 3. Keyboard theming / dark mode
`ui/theme/KeyboardTheme.kt` already defines a light/dark palette behind a
CompositionLocal, but `AgenticKeyboardLayout` hardcodes `Color(0xFF...)`
values throughout (keyboard background, shelf, chips, panels), so the
keyboard ignores system dark mode. Mechanical refactor: route every color
through the theme, then honor `isSystemInDarkTheme()`.

### 4. User-defined palette commands
The slash command palette (`util/CommandPalette.kt`) is a hardcoded list.
The app already has a `ShortcutTemplate` Room entity and a Shortcuts tab in
MainActivity — reuse that plumbing to let users define their own
`/token → rewrite instruction` pairs, merged into `CommandPalette.matches()`.

### 5. Selection-scope indicator
When a text selection is active, the AI actions silently operate on it
(shipped in PR #12). Add a small badge on the AI action row ("acting on
selection") so the behavior is discoverable instead of surprising.

## Later / unscheduled

- Clipboard history (multiple recent clips, not just the current one).
- Long-press accent/symbol popups on keys.
- Multi-variant results: generate 2–3 rewrite candidates and let the user pick.
- Streaming for Continue so the suggestion appears as it generates.
- Instrumented/screenshot tests for the keyboard layout (CI currently only
  builds the APK and runs pure-JVM tests).

## Shipped

- **PR #12** — high-impact cluster: selection-scoped AI actions,
  intent-directed replies, slash command palette, voice-lock setting,
  regenerate button + iterate chips (Shorter/Longer/Warmer/Firmer/More
  formal), plus pure-JVM tests for command parsing and intent mapping.
