# AGENTS.md — Agentickeyboard (Claude, Codex, Jules, Hax and humans)

Stable instructions first so they stay prompt-cached. Changing details live in linked docs.

## Session start (read in this order)
1. This file, then [docs/DECISIONS.md](docs/DECISIONS.md) and [docs/REPO_MAP.md](docs/REPO_MAP.md).
2. Slack #agent-board (below), then the Nexus task: https://github.com/Daddymean/Nexus (START-HERE.md, `tasks/`).
3. Only then open the code you need, in slices.

## Hard rules (never change without Keith)
- **Escalate to Keith only for:** merges and releases, security and secrets, spending, anything irreversible. Decide routine workflow and features yourselves.
- Never close another agent's PR without Keith's approval. Never touch rulesets, branch protection, tokens or CI secrets.
- Branch + PR only; no direct pushes to `main`, no force-pushes to shared branches.
- Never commit secrets. API keys are entered at runtime and stored with Android Keystore, never in the repo or BuildConfig.
- Logs carry no user data or keys (exception class names only).
- Open-source dependencies only, with licences compatible with the project. Do not copy GPL, AGPL or FUTO code.

## Privacy rules (keyboard and AI twin)
- Never learn from or store text from password, incognito/no-personalized-learning or other sensitive fields.
- AI twin data is on-device only: no Supabase, cloud or network path (guarded by `TwinCloudIsolationTest`). Backups stay encrypted on the device until Keith moves them himself.
- Twin PRs touching personal data, security or the microphone open as **drafts marked HOLD for Keith** and merge only on his explicit go-ahead.
- Voice: open-source, commercially usable models only (Chatterbox, OpenVoice V2). No F5-TTS or XTTS. Raw recordings are kept untouched.
- Cloud AI calls go through the redaction guard ([docs/cloud-redaction-guard.md](docs/cloud-redaction-guard.md)).

## Agent board (Slack #agent-board, channel `C0CCDKMHJD6`)
- Read #agent-board at the start of every task, before claiming work.
- **Claim before you start:** post a claim (or 👀 an existing one) so no two agents do the same task.
- Top-level post format: `[IDEA|CONCERN|DECISION|HANDOFF|QUESTION] repo · agent · open|ack|done`, then the text.
- Replies go in the post's thread. Reactions: 👀 ack, ✅ done, 🛑 needs Keith.
- Coordination only: never post secrets, tokens, keys or personal data. Escalations still go to Keith (see Hard rules).

## Review and token policy (Nexus ADR-0007, supersedes ADR-0005's two-review rule)
- **One reviewer per PR.** A second reviewer only for security-sensitive PRs: privacy, crypto, microphone/audio, secrets/keys, permissions.
- **Local tests must pass before asking for review** (state the command and result; unrun checks are `not_run`).
- **Prefer slightly larger slices with fewer review rounds:** one coherent, tested change per PR rather than many tiny ones.
- **Routine work goes to cheaper models:** summaries, lint fixes, CI triage, briefs and handoff notes.
- **Lean sessions:** one task per session, read files in slices, edit instead of rewrite, don't re-read to verify, don't poll CI in loops (CI posts a one-line pass/fail summary per commit).
- **Jules:** Keith must turn off the Jules schedule himself; agents must not change it.

## Nexus process
Claim the task in Nexus first, then write handoff JSONL under `communications/` and update `tasks/`. Use real `date -u` timestamps. Every commit ends with an `Agent: <name> (<runtime>)` trailer. Pair each app PR with its Nexus records PR.

## Build and test
- CI (`.github/workflows/android-build.yml`) is the source of truth: debug + release build, unit tests, screenshots, lint, then a pass/fail summary on the commit.
- Cloud sessions without an Android SDK must not try to build locally; see [docs/agent-environment.md](docs/agent-environment.md). Sessions with an SDK run unit tests + `lintDebug` before asking for review.
- Never claim a local build passed unless you ran it.

## Conventions
Match neighbouring code style. One focused change per commit, descriptive messages, feature branches only, clean working tree when you stop.
