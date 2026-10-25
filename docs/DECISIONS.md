# Decisions — Agentickeyboard

Short, dated, newest first. Full records: [Nexus decisions/](https://github.com/Daddymean/Nexus/tree/main/decisions) and [projects/agentickeyboard/DECISIONS.md](https://github.com/Daddymean/Nexus/blob/main/projects/agentickeyboard/DECISIONS.md). Add a line here in the same PR as any new decision.

- **2026-10-23** — Token-saving and review policy ([ADR-0007](https://github.com/Daddymean/Nexus/blob/main/decisions/ADR-0007-review-policy-and-token-savings.md)): one reviewer per PR, a second only for security-sensitive PRs; local tests before review; larger slices; routine work to cheaper models; Slack #agent-board for claims; trimmed AGENTS.md/CLAUDE.md; CI posts a per-commit summary. Supersedes ADR-0005's two-review rule. Merges and releases still need Keith.
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
