# CLAUDE.md

Guidance for AI coding agents (and humans) working in this repository.

## Project

Agentic Keyboard (Lumina AI Keyboard) — an Android IME (custom keyboard) built
with Kotlin, Jetpack Compose, Room, Retrofit/Moshi, and the Gemini API.

- Android Gradle Plugin: **9.1.1** (requires **Gradle 9.1.0+** and **JDK 17+**)
- `minSdk` 24, `compileSdk`/`targetSdk` 36

## Build & test environment — READ THIS FIRST

**This repository is developed in a cloud CLI environment that does NOT have an
Android SDK installed.** Do not attempt to build or test Android code locally
here. Specifically:

- **Do NOT run local Gradle builds or tests** (`./gradlew assembleDebug`,
  `./gradlew testDebugUnitTest`, `./gradlew lint`, etc.). They will fail because
  there is no Android SDK, and often no network access to download one.
- **Do NOT create or expect a `local.properties` file** or an `sdk.dir` /
  `ANDROID_HOME` / `ANDROID_SDK_ROOT`. Their absence is expected, not a bug to
  "fix".
- **Do NOT try to install the Android SDK, build-tools, or platform images.**
- The locally available `gradle` (if any) may be an older version than this
  project requires and must not be used to build the project.

**All builds and tests run in GitHub Actions CI, not locally.** The workflow at
`.github/workflows/android-build.yml` builds a debug APK on every push and pull
request to `main`/`master` and uploads the APK as an artifact.

### How to validate changes without a local build

Since you cannot compile here, verify code changes by:

1. Careful review against the surrounding code and Kotlin/Compose semantics.
2. Structural sanity checks (balanced braces/parens, no stale references to
   renamed/removed symbols, imports present).
3. Keeping unit-test logic (in `app/src/test/`) pure JVM where possible so it
   is exercised by CI.
4. **Pushing to a branch and letting GitHub Actions build.** Read the CI result
   (and job logs on failure) to confirm the build passes — that is the source of
   truth for "does it compile / do tests pass", not a local run.

When reporting status, never claim a local build or test run succeeded. Say the
change was verified by review and defer the authoritative pass/fail to CI.

## Repository layout

```
app/src/main/java/com/example/
├── AgenticKeyboardApplication.kt   # App singleton: Room repo, settings, swipe dictionary
├── MainActivity.kt                 # Companion app (Console / Shortcuts / Style Hub / Setup)
├── service/AgenticKeyboardService.kt  # The InputMethodService hosting the Compose keyboard
├── ui/
│   ├── AgenticKeyboardLayout.kt    # Keyboard UI: keys, AI shelf, gestures, swipe-to-type
│   ├── KeyboardViewModel.kt        # State + AI orchestration + on-device learning
│   └── theme/KeyboardTheme.kt      # Keyboard color palette (light/dark) — CompositionLocal
├── network/                        # Retrofit client + Gemini request/response + GeminiManager
├── db/DatabaseModels.kt            # Room entities, DAOs, repository
└── util/
    ├── SwipeToTypeEngine.kt        # Swipe path-matching decoder + loadable dictionary
    ├── KeyboardSettings.kt         # SharedPreferences-backed persistent settings
    └── PersonalModelSerializer.kt  # Privacy-aware personalization export/import
```

## Conventions

- Match the existing code style and structure of neighbouring files.
- Keep each logical change in its own focused commit with a descriptive message.
- Develop on a feature branch; do not push directly to `main`.
- Commit and push only when a change is complete; keep the working tree clean.
