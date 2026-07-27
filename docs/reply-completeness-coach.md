# Reply Completeness Coach

## Purpose

The Reply Completeness Coach is an advisory pre-send check for messages that
contain more than one explicit question or request. It estimates whether the
current reply draft addresses each topic and can surface a reminder such as:

> This reply may answer 1 of 3 requests. Check: invoices, quote.

The feature is not a semantic guarantee. It is a conservative local heuristic
intended to catch obvious omissions without turning Send into an interrogation.

## Local analysis model

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

## Explicit context and Send integration

The keyboard never scrapes another app's conversation. The user copies the
message they are answering and taps **Use clipboard** in the Reply Coach bar.
Only that tap reads the current clipboard. A bounded preview lets the user
verify, replace, or clear the attached context.

`ReplyCompletenessSession` keeps the selected context and transient warning
state in memory. It coordinates the Send action without entering
`AiPanelState`, hostile-tone Send Guard state, Room, SharedPreferences, or the
AI request lifecycle.

For editors whose IME action is Send:

1. the service asks the completeness session whether the explicitly attached
   context and current draft justify an advisory,
2. if no completeness warning is needed, the existing hostile-tone Send Guard
   runs independently,
3. if neither advisory pauses the action, the editor's Send action proceeds.

The warning offers:

- **Keep editing** — clears the warning but keeps context, so the edited draft
  is checked again,
- **Dismiss this draft** — suppresses the warning only for the identical
  context/draft pair,
- **Send anyway** — bypasses completeness analysis for the next Send attempt,
- a second Send on the same armed draft — also proceeds, matching the existing
  reversible Send Guard interaction.

If both completeness and hostile-tone advisories apply, they can appear in
sequence because they remain separate safeguards with separate dismissal
state. Neither becomes a permanent block.

## Lifecycle and privacy boundary

Analysis is synchronous and in memory. The assessment does not contain the
whole incoming message or draft. It contains only derived counts, up to three
short missing-topic labels, confidence, and advisory text.

The active UI state contains a bounded, user-visible context preview so the
user can verify what they deliberately attached. Context is capped at 8,000
characters for predictable IME work. The full bounded context remains private
to the in-memory session and is discarded when:

- the user taps Clear,
- a new editor session starts,
- input finishes,
- or a secure field is encountered.

The feature does not:

- persist incoming messages, drafts, obligations, assessments, or dismissals,
- upload text or call Gemini,
- inspect contacts or recipients,
- capture clipboard contents in the background,
- read another app's conversation automatically,
- rewrite or automatically send a message,
- hard-block Send,
- award Mastery XP or change progression.

## Known limitations

Topic matching is lexical and intentionally bounded. It can miss paraphrases,
pronoun-only answers, implied answers, or domain-specific synonyms. It may also
interpret some polite multi-clause prose as a request. Conservative extraction,
minimum evidence, and confidence thresholds reduce false warnings.

A future model-assisted fallback may be evaluated, but it must remain optional.
On-device execution is preferred; any cloud fallback must use the existing
redaction boundary and may never be required in order to send.

## Test surface

Pure-JVM tests cover extraction and matching plus the integration coordinator:
explicit capture, bounded previews, partial and complete replies, secure-field
cleanup, same-draft second Send, Keep editing, exact-draft dismissal, one-shot
bypass, draft changes, and full session clearing.

Compose controls expose stable test tags for later instrumented and screenshot
coverage. GitHub Actions remains authoritative for JVM tests, debug assembly,
and release/R8 validation.
