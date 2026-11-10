# Isolated call screening v0 — approved design

Keith approved isolated design, observation-first behavior, no persistent call history,
and CLI implementation; draft PR only, no merge. Coordination belongs to the parent.

## Boundary
Standalone installable `:callscreen` APK, Android 10+ (API 29), compile/target 36.
No dependency on :app, :core, context-app, twin, consent, or TwinPrivacyFilter.
No INTERNET, audio, analytics, VoIP, cloud backup, model, or twin-memory access.
No persistent activity history or caller data in diagnostic logs. Only explicit
rule settings and user-entered allowlist persist in app-private preferences.
Notifications have generic content (no caller identifiers) and are overwritten.

## Behavior
Request ROLE_CALL_SCREENING through system UI; optional READ_CONTACTS and
POST_NOTIFICATIONS. Observation mode always ALLOWs. Active rules require an
explicit confirmation; known contacts and exact allowlist always ALLOW, followed
by a configurable unknown-number action (ALLOW/SILENCE/REJECT). No fuzzy/suffix
matching or caller authentication claims: caller ID can be spoofed.
Numbers must be explicit international +digits (8–15 digits), or US 10/11 digit
numbers normalized with explicit US default. Unsupported/ambiguous input ALLOWs.
Permission revoked, role absent, data inaccessible, contact lookup error, or
internal 2500ms deadline -> ALLOW. Contacts use a bounded lookup off main thread;
a separate scheduled watchdog responds independently, exactly once per call.
Respond before notification. Never modify outgoing calls.

Android supplies only tel calls; known contacts bypass screening without
READ_CONTACTS; restricted/unknown/unavailable/payphone presentations bypass it.
Five-second system timeout ignores late replies: do not rely on it for normal flow.
Silence still surfaces in the dialer. Reject is manual-rejection semantics, not
guaranteed voicemail routing. No interception or answering audio.

## Validation and release gate
Pure JVM tests for normalization, precedence, revoked role/permission and deadline,
Python source/merged-manifest privacy guards, CI debug/release builds, unit tests,
lint. Real-device carrier/role/notification/timeout validation remains required.
No merge/release; security/permissions changes need independent review and Keith.

Sources: https://developer.android.com/reference/android/telecom/CallScreeningService
https://developer.android.com/reference/android/telecom/CallScreeningService.CallResponse.Builder
