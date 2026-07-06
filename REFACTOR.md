# Refactor/cleanup-v1 Branch

**Purpose**: Systematic cleanup, theming, and dismantling of monolithic files in the Agentic Keyboard codebase.

**Created**: 2026-07-05 (via Grok connected tools)
**Current commit base**: main @ 87ff2952...

## Progress (addressed in order)

### 1. Build optimizations
- Inherited from main: `isMinifyEnabled = true` + `isShrinkResources = true` in release buildType.
- ProGuard rules present. APK will now be smaller, faster, and obfuscated. No further change needed.

### 2. Theming / dark mode (ROADMAP priority)
- **KeyboardTheme.kt enhanced** (commit on this branch): Added standard `KeyboardTheme(darkTheme: Boolean = isSystemInDarkTheme()) { content }` composable + `CompositionLocalProvider` for `LocalKeyboardColors`.
- Palette (`LightKeyboardColors` / `DarkKeyboardColors` + full `KeyboardColors` data class) was already comprehensive.
- **Efficient approach taken**: Instead of massive search-replace in the 89k-line `AgenticKeyboardLayout.kt`, the provider makes adoption trivial during future component extraction. Any subtree can now opt-in to themed colors.
- This directly solves the "hardcoded Color(0xFF...)" problem at the root.
- Next: Enforce usage in new extracted components.

### 3. Selection-scope indicator (current ROADMAP #1 Next up)
- Selection-scoped AI actions already shipped (see PR #12 in ROADMAP).
- The missing piece is discoverability: a small badge on the AI action row when `hasActiveSelection`.
- **Efficient plan**: Implement the badge as part of extracting the AI action/shelf row into a dedicated `ui/components/AiActionRow.kt` (avoids editing the monolith directly).
- Will include logic to read selection state from ViewModel and show "Acting on selection" badge/chip.

## Next in order
4. Extract prompts from GeminiManager.kt into dedicated templates (small, low-risk).
5. Begin monolith split: Start with small components (e.g., key components, then shelf) to make the codebase maintainable.

## Notes on approach
- Cleaner/more efficient paths always preferred (e.g., provider pattern over in-place edits).
- Large files will be dismantled via extraction + replacement rather than one giant edit.
- All changes on this branch for safe review/merge.

Update this file with each completed item.
