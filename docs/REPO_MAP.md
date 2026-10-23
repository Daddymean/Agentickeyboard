# Repo map — Agentickeyboard

Read at session start (with [DECISIONS.md](DECISIONS.md)) instead of exploring the tree. Update it in the same PR when you add or move a top-level area.

| Path | What it is |
|---|---|
| `app/` | `:app`, the IME and companion app. Package `io.github.daddymean.agentickeyboard` |
| `app/.../service/AgenticKeyboardService.kt` | InputMethodService hosting the Compose keyboard |
| `app/.../ui/` | Keyboard UI (`AgenticKeyboardLayout.kt`, `KeyboardViewModel.kt` — both large, read in slices), theme |
| `app/.../network/` | Retrofit client, Gemini request/response, `GeminiManager` |
| `app/.../db/` | Room entities, DAOs, repository |
| `app/.../util/` | Swipe decoder, `KeyboardSettings`, personal-model serializer |
| `app/.../twin/` | AI legacy twin: store, cipher, vault, learning filter; `capsule/`, `consent/`, `deadman/`, `export/`, `journal/`, `memory/` |
| `app/.../*Activity.kt` | Screens: Main, Journal, Twin*, DeadMan, SnippetVault, ClipboardHistory, ConversationCapture |
| `app/src/test/` | Pure-JVM unit tests (run in CI); screenshot tests via Roborazzi |
| `core/` | `:core`, context platform contract (Room, Snapshot, AIDL, ContextClient). See [context-core.md](context-core.md) |
| `context-app/` | `:context-app`, IContextService host, collectors, distiller, Supabase sync (WorkManager) |
| `supabase/` | Edge functions, migrations, tests for the context sync backend. Twin data never goes here |
| `tools/twin-export/` | Off-device Python: decrypt export, open release archive, open time capsule |
| `tools/voice/` | Off-device voice-cloning pipeline (Chatterbox / OpenVoice V2) |
| `tools/tests/`, `tools/build_manifest.py` | Artifact manifest tooling + tests (run first in CI) |
| `.github/workflows/` | `android-build.yml` (build, tests, lint, commit summary), `codeql.yml`, `supabase.yml` |
| `docs/` | Feature design notes. Twin: `twin-handwriting.md`, `twin-owasp-llm-top10-2025.md`. Privacy: `cloud-redaction-guard.md` |
| `ROADMAP.md` | Product roadmap |

Coordination, tasks and ADRs live in [Daddymean/Nexus](https://github.com/Daddymean/Nexus) (`tasks/KEYBOARD-*.md`, `decisions/`, `projects/agentickeyboard/`).
