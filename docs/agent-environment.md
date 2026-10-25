# Agent environment and efficiency details

Details linked from [AGENTS.md](../AGENTS.md). Moved here from the old CLAUDE.md so the always-loaded instructions stay short.

## Project

Agentic Keyboard (Lumina AI Keyboard) — an Android IME (custom keyboard) built
with Kotlin, Jetpack Compose, Room, Retrofit/Moshi, and the Gemini API.

- Android Gradle Plugin: **9.1.1** (requires **Gradle 9.1.0+** and **JDK 17+**)
- `minSdk` 34 (required by `:core`, which `:app` depends on; this single-user
  platform targets Android 16), `compileSdk`/`targetSdk` 36

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
`.github/workflows/android-build.yml` builds a debug APK, runs the unit tests,
and compiles the R8-minified release APK (unsigned; signing needs the upload
keystore) on every push and pull request to `main`/`master`, uploading both
APKs as artifacts.

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

See [REPO_MAP.md](REPO_MAP.md).

## Conventions

- Match the existing code style and structure of neighbouring files.
- Keep each logical change in its own focused commit with a descriptive message.
- Develop on a feature branch; do not push directly to `main`.
- Commit and push only when a change is complete; keep the working tree clean.

## Working efficiently (keep token usage low)

This project is worked on under tight usage limits. Default to lean mode:

- **One task per session.** Finish a feature, push, then start a fresh session
  with a short handoff brief rather than continuing a long conversation — a long
  chat re-sends its whole history every turn.
- **Read in slices, not whole files.** Use Read `offset`/`limit` (or Grep) to
  pull only the lines you need. `AgenticKeyboardLayout.kt`, `KeyboardViewModel.kt`,
  and `MainActivity.kt` are large — never read them end-to-end without reason.
- **Edit, don't rewrite.** Use targeted `Edit` (old→new string) on the lines that
  change. Do not `Write` a whole large file to change a few lines.
- **Don't re-read a file just to verify an edit.** The harness reports edit
  success; a re-read is wasted tokens.
- **Lean on CI instead of exhaustive manual review.** The pipeline compiles the
  code — push and read the (small) CI status rather than reasoning through every
  line locally.
- **Prefer small GitHub calls.** Use `pull_request_read` `get_status` /
  `get_check_runs` and pass `minimal_output: true`. Avoid `actions_list` /
  `get_workflow_run` full objects — they return very large payloads.
- **Don't poll.** Push once and check status a single time (or let the user
  report back); don't loop on waits.
