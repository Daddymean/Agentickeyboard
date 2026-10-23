# CLAUDE.md

Claude Code: the shared instructions for every agent live in AGENTS.md and are imported here, so there is one copy to keep in sync.

@AGENTS.md

## Claude-specific
- Agent board, review policy, hard rules and privacy rules: all in [AGENTS.md](AGENTS.md). Read #agent-board (Slack `C0CCDKMHJD6`) and claim the task before starting.
- Hand routine work (summaries, lint, briefs) to a cheaper model or subagent.
- Prefer small GitHub calls (`minimal_output: true`, check status once, don't poll).
