# Reply Completeness Coach

## Purpose

The Reply Completeness Coach is an advisory pre-send check for messages that
contain more than one explicit question or request. It estimates whether the
current reply draft addresses each topic and can surface a reminder such as:

> This reply may answer 1 of 3 requests. Check: invoices, quote.

The feature is not a semantic guarantee. It is a conservative local heuristic
intended to catch obvious omissions without turning Send into an interrogation.

## First slice: pure local model

`ReplyCompletenessCoach` is pure Kotlin and has no Android, network, database,
or model dependency. It:

1. removes Markdown quote lines and reported quoted questions,
2. extracts explicit question and direct-request clauses,
3. splits compound polite requests and coordinated questions,
4. reduces each obligation to a short topic label and matching terms,
5. compares those terms with the reply draft,
6. returns counts, missing topics, confidence, and optional advisory copy.

The model returns `null` rather than guessing when:

- fewer than two reliable obligations are present,
- the draft is blank or only a tiny acknowledgement,
- questions are rhetorical, casual small talk, or quoted material,
- extraction confidence is below the warning threshold.

A non-null assessment can still have `shouldWarn == false` when the draft
appears to address every extracted obligation.

## Privacy boundary

Analysis is synchronous and in memory. The assessment does not contain the
whole incoming message or draft. It contains only derived counts, up to three
short missing-topic labels, confidence, and advisory text.

This slice does not:

- persist incoming messages, drafts, obligations, or assessments,
- upload text or call Gemini,
- inspect contacts or recipients,
- capture the clipboard,
- read another app's conversation automatically,
- send, rewrite, or block a message,
- award Mastery XP or change progression.

The later UI integration must preserve the existing secure-field boundary and
must obtain reply context through an explicit user-visible action.

## Known limitations

Topic matching is lexical and intentionally bounded. It can miss paraphrases,
pronoun-only answers, implied answers, or domain-specific synonyms. It may also
interpret some polite multi-clause prose as a request. Conservative extraction,
minimum evidence, and confidence thresholds reduce false warnings.

A future model-assisted fallback may be evaluated, but it must remain optional.
On-device execution is preferred; any cloud fallback must use the existing
redaction boundary and may never be required in order to send.

## Planned second slice

After the pure model is stable:

- define explicit incoming-context capture,
- add a separate completeness-warning UI state,
- reuse the Send Guard action seam without conflating tone and completeness,
- provide Keep editing, Dismiss, and Send anyway actions,
- clear context on editor changes and secure fields,
- add ViewModel and Compose coverage.

The warning remains advisory and reversible. No draft is automatically changed
and no send action is permanently blocked.
