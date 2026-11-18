# Repo map — Agentickeyboard

Read at session start (with [DECISIONS.md](DECISIONS.md)) instead of exploring the tree. Update it in the same PR when you add or move a top-level area.

| Path | What it is |
|---|---|
| `app/` | `:app`, the IME and companion app. Package `io.github.daddymean.agentickeyboard` |
| `app/.../service/AgenticKeyboardService.kt` | InputMethodService hosting the Compose keyboard |
| `app/.../ui/` | Keyboard UI: `AgenticKeyboardLayout.kt` (one large composable) with its buttons and result views in `KeyboardLayoutParts.kt`; `KeyboardViewModel.kt` (one large class) with its state types in `KeyboardUiModels.kt`. Read the two large ones in slices. Theme |
| `app/.../network/` | Retrofit client, Gemini request/response, `GeminiManager` |
| `app/.../db/` | Room entities, DAOs, repository |
| `app/.../util/` | Swipe decoder, `KeyboardSettings`, personal-model serializer |
| `app/.../twin/` | AI legacy twin: store, cipher, vault, learning filter; `capsule/`, `consent/`, `deadman/`, `export/`, `journal/`, `memory/` |
| `app/.../*Activity.kt` | Screens: Main, Journal, Twin*, DeadMan, SnippetVault, ClipboardHistory, ConversationCapture |
| `app/.../*Tab.kt`, `MainScreenComponents.kt` | The companion app's tabs (`PlaygroundTab`, `ShortcutsTab`, `ExportTab` = Style Hub and settings, `SetupTab`) and their shared rows and cards; `MainActivity.kt` only hosts them |
| `app/src/test/` | Pure-JVM unit tests (run in CI); screenshot tests via Roborazzi |
| `callscreen/` | `:callscreen`, standalone local-only APK; observation-first call rules, no audio/network/history; see `callscreen/README.md` |
| `core/` | `:core`, context platform contract (Room, Snapshot, AIDL, ContextClient). See [context-core.md](context-core.md) |
| `context-app/` | `:context-app`, IContextService host, collectors, distiller, Supabase sync (WorkManager) |
| `supabase/` | Edge functions, migrations, tests for the context sync backend. Twin data never goes here |
| `tools/twin-export/` | Off-device Python: decrypt export, open release archive, open time capsule |
| `tools/voice/` | Off-device voice-cloning pipeline (Chatterbox / OpenVoice V2) |
| `tools/cloud-gradle.sh` | Local build/test in cloud sessions: installs the Android SDK on first use, then runs Gradle with retries. See [agent-environment.md](agent-environment.md) |
| `tools/tests/`, `tools/build_manifest.py` | Artifact manifest tooling + tests (run first in CI) |
| `.github/workflows/` | `android-build.yml` (build, tests, lint, commit summary), `codeql.yml`, `supabase.yml` |
| `docs/` | Feature design notes. Twin: `twin-handwriting.md`, `twin-owasp-llm-top10-2025.md`. Privacy: `cloud-redaction-guard.md` |
| `ROADMAP.md` | Product roadmap |

Coordination runs on Slack #agent-board; decisions are recorded in `docs/DECISIONS.md`. [Daddymean/Nexus](https://github.com/Daddymean/Nexus) is retired and read-only: older tasks (`tasks/KEYBOARD-*.md`) and full ADRs (`decisions/`) stay there for reference only.
