# Decisions — Agentickeyboard

Short, dated, newest first. This file is now the record of decisions. Current merge and review rules: [2026-11-17 entry](#merge-rules-2026-11-17) (confirmed by Keith on 2026-11-17). Older full records (read-only, Nexus is retired): [Nexus decisions/](https://github.com/Daddymean/Nexus/tree/main/decisions) and [projects/agentickeyboard/DECISIONS.md](https://github.com/Daddymean/Nexus/blob/main/projects/agentickeyboard/DECISIONS.md). Add a line here in the same PR as any new decision.

- **2026-11-25** — Keith answered #231's open questions ("1. Yes 2.yes", 5:26 PM PT): when learning is paused, grammar fix and tone check do not save to the writing log. Encrypting the keyboard database like the twin's is a separate HOLD PR.
- **2026-11-19** — Keith answered #230's open questions ("1.yes 2.yes 3.yes", 11:55 PM PT): the keyboard's own learned data gets its own viewer/delete screen, separate from the twin's (KEYBOARD-047, `KeyboardLearnedActivity`); the twin's date-range delete keeps sparing core memories and journal answers; the never-learn phrase list stays hash-only and can't show its phrases. Keyboard DB deletes run with `secure_delete` and a WAL TRUNCATE checkpoint.
- **2026-11-17** — <a id="merge-rules-2026-11-17"></a>**Confirmed by Keith on 2026-11-17.** Replaces ADR-0007 §9 ("agents never merge"). Current review and merge rules, recorded after Keith's Nov 14, 2026 8:21 PM PT open-ended mandate (his words: "Yes, give them the open-ended mandate"):
  - Agents may keep improving, refactoring and upgrading Agentickeyboard, Nexus and Vault, and may propose features themselves.
  - Agents may merge with green CI. Agentickeyboard `main` still needs Keith's admin merge because of the admin-only ("Nopush") ruleset.
  - Secrets, spending, security sign-offs (including new permissions) and irreversible actions still go to Keith.
  - Privacy changes count as security-sensitive: they need two independent reviewers, and Keith merges them.
  - A second independent reviewer is also required for other security-sensitive PRs (crypto, microphone/audio, secrets/keys, Android permissions), per the ADR-0007 §1 rule that still stands.
  - Claim work and post ideas on Slack #agent-board.
- **2026-11-17** — Keith retired Nexus: frozen read-only, no new records, branches or PRs (he archives it himself). Claims and handoffs go on #agent-board, decisions in this file, agent memory in `agent-ops/lessons` + `tasks.db` (no twin, personal or secret data). App PRs no longer need a paired Nexus PR.
- **2026-11-15** — Keith asked for the newest versions: Compose BOM 2026.09, Kotlin 2.4.21, AGP 9.5.1, Gradle 9.9.1, OkHttp 5, Retrofit 3 and the rest of the catalog; `compileSdk` 37.2 (`targetSdk` stays 36; `:callscreen` untouched). The Gemini key is read only from the key saved in the app: the `BuildConfig`/`.env` fallback and the Secrets Gradle plugin are removed (#215).

- **2026-11-08** — Keith approved isolated `:callscreen` APK, observation-first rules, no persistent call history, and CLI implementation. Draft HOLD PR only; twin/consent/filter remain untouched. Design: `superpowers/specs/2026-11-08-call-screening-design.md`.

- **2026-10-23** — Token-saving and review policy (historical ADR-0007; current merge authority is the [2026-11-17 entry](#merge-rules-2026-11-17), confirmed by Keith on 2026-11-17): one reviewer per PR, a second only for security-sensitive PRs; local tests before review; larger slices; routine work to cheaper models; Slack #agent-board for claims; trimmed AGENTS.md/CLAUDE.md; CI posts a per-commit summary. Supersedes ADR-0005's two-review rule.
- **2026-10-23** — Keith merged #183 (supervised mode, time capsules), #185 (offline handwriting; recognition off until Keith enables it on the phone), #186 (computer-side voice cloning).
- **2026-10-19** — #181/#182 as built: retirement undoable for 30 days, Keith's preview skips the consent check, no reminders.
- **2026-10-19** — Approved and core memories go into the export and release archive (#180).
- **2026-10-18** — Release archive: Shamir 2-of-3 guardian shares wrap a data key (re-keying never re-encrypts). Kids labelled Kid 1–6; at 18 a kid may replace a guardian as key holder. No drand time-lock.
- **2026-10-18** — Dead-man trigger: Google Inactive Account Manager after 6 months, email is a signal only; no secrets in Google (#176).
- **2026-10-14** — Twin export: AES-256-GCM, PBKDF2-SHA256 600k rounds, passphrase 12+ chars; backup stays encrypted on the device until Keith moves it manually; no cloud upload (#171).
- **2026-10-12** — Twin is on-device only; never learns from password, incognito or sensitive fields; open-source tools only; no F5-TTS/XTTS; no GPL/AGPL/FUTO code. Twin PRs touching personal data, security or the mic are draft + HOLD for Keith.
- **2026-10-09** — ADR-0006: open-hold merge check, advisory `nexus.py queue`, review time box.
- **2026-10-06** — ADR-0005: in-scope merges delegated to agents (review count now per ADR-0007).
- **2026-10-04** — ADR-0004: keyboard beta declared ready and in use.
- **ADR-0002** — Cloud redaction round-trip for AI calls. **ADR-0001** — repository authority, identity and privacy.
