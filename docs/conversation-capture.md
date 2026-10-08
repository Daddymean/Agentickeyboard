# KEYBOARD-005: explicit visible conversation capture

Keith requested this implementation on October 7, 2026 PT. Nexus task/claim:
https://github.com/Daddymean/Nexus/pull/22
Baseline: de3627ad4f47f96269f57e3367db540120cc40ec.

## User flow

Open the keyboard's AI tools, tap **Use conversation**. First use opens an
in-app disclosure and per-app consent before Android's accessibility settings.
Enable **Lumina conversation capture**, return to the conversation and tap again.
Select the visible text blocks containing the message being answered, then tap
**Attach selected text**. Reply Coach now checks that context locally. Reply
Ideas, Summarize, Translate and Explain reuse the same selection. Clipboard
capture remains available. Capture settings lets the user disable a single app
or revoke the service in Android settings.

## Data and permissions

- The service has Android's user-granted window-content capability, protected by
  BIND_ACCESSIBILITY_SERVICE. It is not declared as an accessibility tool.
- Only the focused host application's visible, non-editable leaf text is read,
  only after capture/confirm/AI/Send taps or completion of an initiated AI action.
  Preview and subsequent use revalidate the original screen fingerprint.
- Text under the keyboard, other packages, invisible nodes, editable subtrees,
  password nodes and accessibility-sensitive subtrees are excluded. A visible
  password or an overlarge tree causes the capture to fail closed. Secure and
  incognito editors bypass the entire feature.
- The reader cannot reliably label incoming/outgoing messages. Names, timestamps
  or app controls may appear; the user explicitly chooses the context blocks.
  It does not scroll, take screenshots, fetch history, operate gestures or send.
- Capture is bounded to 256 visited nodes, the last 40 visible text blocks and
  8,000 characters. Oversized trees fail; truncated text is labeled.
- Only package consent is persisted. Captured text, selections, leases and
  generated panels are transient. Context expires after 60 seconds from capture
  and clears on editor start/finish, keyboard hide, destruction, window focus/state
  changes, explicit Clear or a failed fresh-screen comparison. Changing chats in
  the same app is checked at use even if it emits no window event.
- Selected source text is not written to Room, clipboard, logs or writing-model
  training. Captured-context replies/summary/translation/explanation do not read
  or write the shared response caches. User-applied text follows ordinary editor
  behavior; this feature cannot retract a cloud request already sent.
- Capture and Reply Coach do not use the network. Explicit AI actions use existing
  offline routing or cloud Gemini/redaction. Setup disclosure and the attached
  context toolbar explain that cloud boundary. Existing redaction is not a promise
  that all private information is removed. Offline Explain is unavailable and
  translation is an existing preview, not a new local translation engine.

## Validation

Added policy/lease tests for app/editor/hidden-text filtering, order, duplicates,
bounds, same-app chat changes, window changes, null reads, expiry and clearing.
ViewModel tests cover fail-closed context validation, clipboard compatibility,
intent-to-generation validation and secure/incognito suppression. Android builds
and tests run in CI under CLAUDE.md; no local Gradle run is claimed.

All physical checks remain **not_run**: consent deny/allow/revoke; foreground app
selection with keyboard open; text preview/selection; Reply Coach and each AI
tool; same-app conversation change while generating; cross-app switch; timeout;
secure/incognito fields; unsupported/custom-rendered apps; portrait, landscape,
split-screen and service interruption. Use an identified APK/source SHA for each.
App coverage is unverified until these checks run. Google Play distribution needs
the Accessibility API declaration/disclosure review; no publication is performed.

## Independent review and rollback

Claude and Hax must inspect the same final application head under ADR-0005,
including runtime permission scope, data retention, cache suppression, window
selection and lifecycle behavior. CI alone is not merge authority. Rollback is a
reviewed revert PR; users can disable capture independently in Android settings.

Primary references:
- https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo
- https://support.google.com/googleplay/android-developer/answer/10964491
