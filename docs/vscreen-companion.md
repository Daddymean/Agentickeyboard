# Agent-VScreen companion (draft, HOLD for Keith)

Isolated package: `io.github.daddymean.agentickeyboard.vscreen`.

A default-off bridge from Agentic Keyboard to an installed
[Agent-VScreen](https://github.com/kilolonion/agent-vscreen) on this phone.
It talks only to `127.0.0.1` (or `localhost` / `::1`), port 18765 by default,
and sends the user-pasted token only as an `Authorization: Bearer` header —
never as `?token=`, never in the path, never in the JSON body.

The feature stays off (`VscreenPreferences.DEFAULT_ENABLED = false`, and
`VscreenAction.enabled` defaults to false) until Keith signs this off and
the user opts in. This PR does not wire a settings UI or call the network
from the IME. `VscreenCompanion.plan` is the only entry: it either returns a
ready `POST /v1/run` request or a refusal. Nothing is sent when the privacy
gate blocks the target.

Privacy reuses `EditorPrivacy` and `TwinPrivacyFilter`, and also refuses
payment / checkout wording. Blocked: password fields, incognito
(`IME_FLAG_NO_PERSONALIZED_LEARNING`), OTP / card / other sensitive hints,
one-time codes, payment labels, and packages on the secure-apps list.

Security-sensitive: two reviewers, draft HOLD, no merge without Keith's
explicit go-ahead. Token storage in this slice is a private preference;
wrapping it with Android Keystore (as `GeminiKeyStore` does) should land
before any UI that accepts a paste.
