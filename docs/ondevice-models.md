# On-device models (Gemma 4 E2B and EmbeddingGemma 2)

Shared package: `io.github.daddymean.agentickeyboard.models` (Hax, draft #209).

- **Catalog** — `ModelCatalog`. EmbeddingGemma 2 (twin memory) and Gemma 4 E2B (keyboard rewrite) are both pinned (URL to a Hugging Face commit, SHA-256, exact byte size).
- **Manager** — `ModelManager` / `ModelConsent` / `ModelBytesFetcher`. Download only after an explicit opt-in (default off). SHA-256 is verified; a mismatch deletes the file. Storage is `noBackupFilesDir/models/`. `ModelManager.forApp(context)` is the production wiring.
- **Keyboard routing** — Nano if available and allowed (`BACKGROUND_USE_BLOCKED` / `BUSY` fall through), then local Gemma 4 E2B if opted in, the file is ready, and the device is supported (arm64/x86_64 and at least 6 GB RAM). Rewrite prefers Gemma when it is eligible and falls back to the ML Kit rewriter; a 20s timeout, init/inference exceptions, a missing or corrupt model, or an unsupported/low-RAM device all take that ML Kit path. If ML Kit also fails after an engine was attempted, the keyboard shows a fixed "On-device rewrite is unavailable" state instead of a silent no-op. Otherwise existing heuristics run. No silent cloud fallback. Password, OTP, payment and incognito fields are blocked by `EditorPrivacy` and `TwinPrivacyFilter`.
- **Runtime** — LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android:0.19.1` (Apache-2.0). The AAR ships Kotlin 2.4 metadata and Java 21 bytecode; this repo stays on Kotlin 2.2.10 and Java 11. Calls go through Java reflection (`LiteRtGemmaEngine`, and `LiteRtTextEmbedder` in #209) plus `-Xskip-metadata-version-check` and R8 keep rules. Do not import `com.google.ai.edge.litertlm` from Kotlin. GPU backend with CPU fallback for Gemma.
- **Keyboard extras** — the settings UI shows the size before download and uses Wi-Fi by default (`ModelDownloadPolicy`). Turning the toggle off does not delete the 2.59 GB file; Delete does, via `ModelManager.setOptedIn(spec, false)`.

See also `docs/twin-embedding-search.md` in #209 for the manager API.
