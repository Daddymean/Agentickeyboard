# On-device EmbeddingGemma 2 memory search

Optional semantic search over **approved** twin memories. Off by default.
Keith's words, 9 November 2026, 7:48 PM PT: "Lets implement gemma 4 e2b and
embeddinggemma into the stack."

## What ships

- **Runtime:** LiteRT-LM Android API `com.google.ai.edge.litertlm:litertlm-android:0.19.1` (Apache-2.0). ELF `DT_NEEDED` on `liblitertlm_jni.so` is only Android system libraries (`libandroid`, `libz`, `libGLESv2`, `libEGL`, `libm`, `liblog`, `libdl`, `libGLESv3`, `libc`). CPU backend only; vendor NPU AARs are not added. The AAR is published with Kotlin 2.4 metadata and Java 21 class files; this repo still compiles with Kotlin 2.2.10 and Java 11 bytecode on JDK 17, so `LiteRtTextEmbedder` calls the public EmbeddingEngine API by reflection (signatures checked with javap against 0.19.1) and Kotlin is given `-Xskip-metadata-version-check` because the AAR is still on the compile classpath. R8 keep rules are in `app/proguard-rules.pro`. Bumping Kotlin and the JDK would be cleaner and may be what the Gemma 4 E2B rewrite wants; this PR does not do that bump.
- **Model:** official `litert-community/embeddinggemma-2-text-270m-litert-lm` revision `9be6e8b90982095dc05c2bd162e4b954ee4dbac7`, file `embeddinggemma-2-text-270m.litertlm`, 164,626,432 bytes, SHA-256 `2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb`, license Apache-2.0, ungated. The older gated `google/embeddinggemma-300m` (Gemma Terms) is not used.
- **Download:** only after the user opts in on the twin settings screen. [ModelManager] verifies the SHA-256 pin and deletes a file that does not match. The file is stored under `noBackupFilesDir/models/`.
- **Index:** `noBackupFilesDir/twin/memory-vectors.sealed`, AES-256-GCM with the existing twin Keystore key (AAD `vector-index-v1`). The plaintext payload is memory ids and float vectors only, never memory text. Delete-all removes it and shreds the key.
- **Privacy:** [TwinPrivacyFilter] and [TwinTextFilter.prepare] run before any text is embedded. "Never learn in these apps" is honoured. Queries from a blocked field return no hits. Query embeddings are ephemeral and are not stored.
- **Fallback:** if the model is missing or opt-in is off, search uses the existing in-memory FTS4 / BM25 [MemoryIndex].

## ModelManager API (for the Gemma 4 E2B rewrite)

Package `io.github.daddymean.agentickeyboard.models`. This is a generic opt-in, hash-pinned downloader, not embedding-specific.

- `ModelSpec(id, fileName, downloadUrl, sha256, sizeBytes, license, source)`
- `ModelConsent(context)` — `isOptedIn(id)` defaults to **false**; `setOptedIn(id, Boolean)`
- `ModelBytesFetcher` — `fetch(url, dest)`; production `OkHttpModelFetcher()`, tests inject a fake
- `ModelManager(rootDir, consent, fetcher)`
  - `ModelManager.forApp(context)` / `ModelManager.dir(context)` — `noBackupFilesDir/models`
  - `isOptedIn(id)`, `setOptedIn(spec, Boolean)` (false also deletes that model's files)
  - `download(spec): DownloadResult` — `NotOptedIn` (writes nothing), `Ready(file)`, `HashMismatch(actual)`, or `Failed`
  - `verifiedFile(spec): File?` — present only when the pin matches; a mismatch is deleted
  - `isReady(spec)`, `delete(spec)`, `fileFor(spec)`
- Pins live in `ModelCatalog`. Add a new `ModelSpec` for Gemma 4 E2B and reuse the same manager.

## Unverified

On-device embed latency and RAM were not measured on a real phone. Published LiteRT-LM figures for this 270M file are on the model card (for example 27.1 ms / 334 MB CPU on an S26 Ultra); that is not our measurement. Unit tests use a fake embedder. `assembleDebug` confirms the LiteRT-LM AAR links; it does not run a forward pass.
