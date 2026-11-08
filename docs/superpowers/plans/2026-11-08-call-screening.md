# Call screening v0 Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans task-by-task.

**Goal:** Standalone observation-first, local-only call screener without history.
**Architecture:** Pure Kotlin rule/once-only response engine, Android service adapter,
private rule preferences, system role/permission onboarding and native widget UI.
**Tech Stack:** AGP 9.1.1 built-in Kotlin, Android API 29–36, JUnit4, Python guards.

## Global Constraints
No audio/network/analytics/cloud backup/twin/VoIP or persistent call history.
Only allowlist/settings persist. No caller identifiers in logs/notifications.
Do not touch Hax checkout or owned twin/consent/filter files. Draft HOLD PR only.

### Task 1: Red tests and isolated configuration
- [x] Write `tools/tests/test_callscreen_privacy.py` requiring standalone manifest,
  role binding, no backup/forbidden permissions or dependencies; run with
  `python3 -m unittest discover -s tools/tests -p test_callscreen_privacy.py -v`.
  Expected red: absent manifest/module.
- [x] Add `callscreen/src/test/java/io/github/daddymean/callscreen/ScreeningPolicyTest.kt`
  covering exact normalization, observation, allowlist/contact precedence,
  role/permission failure, and deadline/response-once behavior.
- [x] Include :callscreen and its Android application build file (min29/target36),
  manifest and backup exclusions. No dependency on other project modules.

### Task 2: Rule engine and Android adapter
- [x] Add `ScreeningPolicy.kt`: normalize(String?): String?, decide(Input): Action;
  observation/default failures ALLOW; known/allowlist override unknown action.
- [x] Add `ResponseGate.kt`: atomic respond-once gate with injected monotonic clock
  and 2500ms deadline. Exceptions, permissions and late worker completion ALLOW.
- [x] Add `RuleStore.kt`: persist only settings and manually entered normalized
  allowlist; observation by default with confirmed opt-in persisted, no call event writes.
- [x] Add `ScreeningService.kt`: incoming-only, system role/contacts permission
  checks, cancellable ContactsContract lookup, independent watchdog, response
  mapping and generic notification. No network/audio/diagnostic caller data.
- [x] Add `MainActivity.kt`: role request, permissions, allowlist editor, unknown
  action selector, explicit confirmation for active mode, generic limitations.

### Task 3: Guard, CI and handoff
- [x] Add `tools/check_callscreen_manifest.py` for merged XML; test guard against
  injected forbidden permissions, export/backup regressions.
- [x] Add `.github/workflows/callscreen.yml`: build both variants, unit tests,
  lint, merged manifest guard, APK and test/lint artifacts, no secrets.
- [x] Update repo map and decisions; add real-device checklist.
- [x] Run Python baseline/privacy tests and `git diff --check`; Android Gradle
  commands run in CI only (local JDK/SDK absent; installation forbidden by repo).
- [x] Review privacy/dataflow, commit with Agent trailer, push once and create
  DRAFT HOLD PR. Report actual CI status once, no merge or release.

Self-review: all approved requirements map to a task. JVM red/green execution is
blocked locally by absent JDK/SDK; do not represent static checks as Android tests.

## Execution evidence
- Baseline: 10 Python tests passed before implementation.
- Source privacy tests: observed three intended missing-feature failures, then green.
- Merged-manifest guard tests: observed three missing-guard failures, then green.
- Deadline isolation guard: observed missing effects executor failure, then green.
- Final Python suite: 17 passed; source manifest guard and git diff --check passed.
- Kotlin tests were written before their implementations, but were not run locally:
  no JDK/SDK; JDK-only apt setup failed with Debian mirror HTTP 500 responses.
  SDK installation/local Android Gradle are prohibited by repository guidance.
- Android build, JUnit and lint deferred to dedicated PR CI; no real-device validation.
- Independent reviewers and Nexus/board coordination remain parent-owned; not done
  by this executor. Draft PR is not an approval or release-readiness claim.
