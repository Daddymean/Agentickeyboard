# Unified AI panel state

The keyboard now stores one active AI panel in `AiPanelState` instead of maintaining separate nullable flows for grammar, tone, summaries, translations, rewrites, composition, explanations, continuations, reply intent, and reply suggestions.

## Why

The previous model allowed several results to remain populated at once. The UI resolved those contradictory combinations through a long priority-ordered `if/else` chain, while `dismissResults()` had to clear every field manually.

The sealed state makes impossible combinations unrepresentable:

- `Idle`
- `Loading`
- `ReplyIntent`
- `Replies`
- `Grammar`
- `Tone`
- `Summary`
- `Translation`
- `Rewrite`
- `Compose`
- `Explanation`
- `Continuation`

Source text now travels with results that support before/after comparison. Refinement eligibility is also defined by the state model rather than by checking several unrelated fields.

Every foreground AI request transitions through `Loading`. A completed result replaces that state atomically, while a cancelled or empty request returns the panel to `Idle`.

Send Guard and the background proofread hint remain separate because they are independent editor safeguards, not AI result panels.
