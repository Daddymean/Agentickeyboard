# Trust-hardening comparison branch

This branch is intended as a focused comparison against `main`. It keeps the existing app concept intact while hardening the privacy, build, persistence, and AI-request paths.

## Implemented in this branch

- Persisted privacy/offline mode through Room so the companion app and IME service read the same setting.
- Added a shared `PrivacyTextSanitizer` for local storage and cloud-bound prompts.
- Sanitized and pruned writing logs before persistence.
- Added an explicit Room migration from version 2 to 3 and enabled schema export.
- Replaced release prototype defaults with minification, resource shrinking, and safer release signing behavior.
- Added explicit Auto Backup and data extraction exclusions for keyboard-derived data.
- Added ProGuard rules for Retrofit/Moshi/Room models.
- Centralized Gemini model/network configuration.
- Wrapped cloud prompts to treat typed text as untrusted input and redact sensitive-looking values.
- Added stale-request guards so older AI responses cannot overwrite newer actions.
- Indexed swipe typing candidates by endpoint keys and boosted personal vocabulary matches.
- Added Android CI for unit tests and lint.

## Deliberate follow-ups

### Package rename

The package is still `com.example` in this branch. A full rename to something like `com.lumina.agentickeyboard` should be done as its own branch because it touches Kotlin package declarations, source paths, generated code assumptions, and Android XML references. Keeping it separate makes the current diff easier to review.

### Compose modularization

`AgenticKeyboardLayout.kt` is still a large composable. The next pass should split it into:

- `KeyboardRoot`
- `AiShelf`
- `GestureOverlay`
- `SuggestionStrip`
- `KeyboardRows`
- `CommandRow`
- `KeyButton`

The state and gesture logic should move into small controller/state-holder classes so screenshot tests and unit tests can target each piece independently.

### Stronger storage protection

The branch reduces retention and redacts logs, but it does not yet encrypt the Room database. For a production keyboard, consider SQLCipher or AndroidX Security backed storage for any persisted text-derived signals.

### Cloud redaction UX

Cloud redaction is currently conservative and always on through `GeminiConfig.CLOUD_REDACTION_ENABLED`. A production UX should expose a clear setting explaining the tradeoff: safer cloud requests versus possible placeholder substitutions in AI results.
