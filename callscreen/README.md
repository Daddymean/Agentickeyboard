# Local Call Screening (v0)

Separate APK/package `io.github.daddymean.callscreen`, not an IME feature. No
project dependencies, internet/audio permissions, analytics, twin memory, or VoIP.
Android 10+; compile/target 36. Requires a phone supporting ROLE_CALL_SCREENING.

## Use
Install the debug APK, open Local Call Screening, select it as the system screening
app (replaces any other selected screener). Initial mode is observation: all calls
are allowed. Contacts permission enables local contact matching; without it this
v0 fails open and cannot activate filtering. Notification permission is optional.

Enter complete +international numbers, or explicit US 10-digit numbers, one per
line; Save validates the entire list atomically. No suffix matching. Choose an
unknown-caller action, then explicitly confirm active filtering. Return to
observation at any time. Settings/allowlist persist locally; no incoming-call
number ever enters storage or debug logs. No call history UI exists. Generic
notifications overwrite each other and expire after 30 seconds; they never show
caller names/numbers, even unlocked. OS/dialer/carrier logs are outside this app.
Backup and device transfer are disabled/excluded for all app storage domains.

Contacts and exact allowlist override unknown actions. Caller ID can be spoofed;
this is nuisance filtering, not caller authentication. Unsupported/ambiguous
numbers and errors allow. An independent 2.5-second watchdog allows on timeout;
worker results after that deadline cannot reject. Calls must be answered to Telecom
within five seconds; severely stalled processes still depend on Android fallback.

Android does not send restricted/private, unknown, unavailable or payphone
presentations to this API. Known contacts bypass it unless READ_CONTACTS is granted.
Only tel incoming calls are acted on; outgoing calls are ignored. SILENCE still
surfaces in the default dialer. REJECT behaves like manual rejection, not a promise
of voicemail delivery. The app cannot answer/transcribe cellular audio.

## Validation
CI: `./gradlew :callscreen:assembleDebug :callscreen:assembleRelease :callscreen:testDebugUnitTest :callscreen:lintDebug`.
Python: `python3 -m unittest discover -s tools/tests -v`.
Manifest guard: `python3 tools/check_callscreen_manifest.py <merged-manifest.xml>`.
No SDK/JDK is present on the authoring box; Android build/unit/lint checks are CI-only.
No claims of real-device validation.

### Required real-device checks before merge/release
- Role grant/deny/revoke, competing screening app, unsupported role device.
- Observation rings; known contacts/exact allowlist ring; explicit unknown silence
  and reject work; verify actual carrier voicemail behavior without promising it.
- Revoked contacts and notifications; process cold start, locked phone, reboot
  before first unlock; no storage access must fail open.
- Slow contact provider, worker exhaustion, rapid overlapping calls, teardown:
  watchdog allows before five seconds and responds once; outgoing untouched.
- Private/unavailable numbers bypass; verify international/local formats and
  dual SIM behavior. Never use an emergency number for testing.
- Notifications contain no identifiers; storage inspection finds rules only;
  uninstall removes local rules; no app backup, network or audio access.

Two independent reviewers are required for privacy/permission changes under
AGENTS.md. Parent handles agent-board/Nexus coordination. Draft HOLD for Keith;
no merge/release without his approval. Existing twin/consent/TwinPrivacyFilter are
not changed.
