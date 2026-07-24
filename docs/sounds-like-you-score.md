# Sounds Like You Score

## Purpose

The result shelf can show a transient estimate of how closely an eligible AI writing result resembles the user's learned writing habits. The score makes local personalization visible without turning it into a gate, grade, reward, or promise of authorship.

## Eligible results

The first release scores result types whose primary job is to produce writing in the user's voice:

- grammar corrections
- persona and explicit-style rewrites
- composed drafts
- continuations

Translations, summaries, explanations, tone analyses, and multi-reply result sets are excluded because matching the user's normal English writing fingerprint is not their primary purpose or would require a different comparison model.

## Evidence model

`VoiceMatchScorer` builds a bounded fingerprint in memory from:

- up to 100 learned vocabulary entries and their local frequency counts
- up to 30 non-correction writing logs
- typical message length
- typical words per sentence
- exclamation, question, and ellipsis rates
- contraction cadence

The weighted score emphasizes familiar vocabulary while still considering structural rhythm. An evidence-derived confidence value keeps a small profile from presenting itself as certainty. When neither five useful vocabulary entries nor three usable samples exist, no percentage is shown.

## Refinement comparison

When a user taps Shorter, Longer, Warmer, Firmer, or More formal, the currently displayed percentage is retained only long enough to compare it with the replacement result. The badge can then show a positive increase, a neutral result, or a point shift. Neither result text nor the baseline percentage is written to storage.

## Privacy boundaries

- Scoring is synchronous, local, and in memory.
- Candidate text and the derived fingerprint are never persisted or uploaded.
- No score history is retained.
- Existing secure-field checks prevent eligible AI results from being created there.
- Existing learning pause controls stop new evidence collection; previously learned local evidence remains available for read-only scoring.
- The UI receives only the percentage, confidence, label, short derived signals, and optional refinement delta.

## Product boundaries

The score must not:

- unlock or hide keyboard capabilities
- award XP or alter streaks
- influence Android input-method setup or default selection
- rank users or compare them with other people
- imply that a result was written by the user
- present a low score as failure

It is a compass for personalization, not a report card.
