# Agent environment and efficiency details

Details linked from [AGENTS.md](../AGENTS.md). Moved here from the old CLAUDE.md so the always-loaded instructions stay short.

## Project

Agentic Keyboard (Lumina AI Keyboard) — an Android IME (custom keyboard) built
with Kotlin, Jetpack Compose, Room, Retrofit/Moshi, and the Gemini API.

- Android Gradle Plugin: **9.5.1** on **Gradle 9.9.1** (wrapper), **JDK 21**
- `minSdk` 34 (required by `:core`, which `:app` depends on; this single-user
  platform targets Android 16), `compileSdk` 37.2, `targetSdk` 36

## Build & test environment — READ THIS FIRST

**Cloud sessions can build and test locally.** A fresh session has a JDK but no
Android SDK; one command installs what the project needs and then runs Gradle:

```
tools/cloud-gradle.sh :app:testDebugUnitTest                              # all app unit tests
tools/cloud-gradle.sh :app:testDebugUnitTest --tests '*LocalSpellingTest*'  # one class, seconds once warm
tools/cloud-gradle.sh testDebugUnitTest lintDebug                         # what to run before asking for review
```

- The first run downloads the SDK (about 470 MB, into `~/android-sdk`, outside
  the repo) and the Gradle dependencies. Allow several minutes; later runs take
  seconds. It writes the git-ignored `local.properties`, so a plain `./gradlew`
  works afterwards too.
- **Maven Central rate-limits cloud sessions** (HTTP 429, which Gradle may also
  report as "Could not find"). The script reruns Gradle while that is the only
  failure; each rerun keeps what was already fetched. A plain `./gradlew` does
  not retry, so prefer the script until the caches are warm.
- It needs `dl.google.com`, `services.gradle.org`, `plugins.gradle.org` and
  `repo.maven.apache.org`. If the session's network policy blocks them, the
  script fails at the download; then fall back to CI and say so. Do not work
  around a blocked host.
- Use the wrapper (`./gradlew`, Gradle 9.9.1), never the older system `gradle`.
- Device and emulator tests (`connectedAndroidTest`) do not run here.

**CI is still the source of truth** (`.github/workflows/android-build.yml`):
debug and R8 release builds, all unit tests, screenshots and lint on every
push and pull request. Run the local checks before asking for review, then let
CI confirm.

When reporting status, state the command you ran and its result. Never claim a
check passed that you did not run; unrun checks are `not_run`.

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
  pull only the lines you need. `AgenticKeyboardLayout.kt`, `KeyboardViewModel.kt`
  and `ExportTab.kt` are large — never read them end-to-end without reason.
- **Edit, don't rewrite.** Use targeted `Edit` (old→new string) on the lines that
  change. Do not `Write` a whole large file to change a few lines.
- **Don't re-read a file just to verify an edit.** The harness reports edit
  success; a re-read is wasted tokens.
- **Test locally, narrowly.** Run the one test class you are changing with
  `tools/cloud-gradle.sh ... --tests`, rather than reasoning through every line
  or waiting on a CI round trip for each attempt.
- **Prefer small GitHub calls.** Use `pull_request_read` `get_status` /
  `get_check_runs` and pass `minimal_output: true`. Avoid `actions_list` /
  `get_workflow_run` full objects — they return very large payloads.
- **Don't poll.** Push once and check status a single time (or let the user
  report back); don't loop on waits.
