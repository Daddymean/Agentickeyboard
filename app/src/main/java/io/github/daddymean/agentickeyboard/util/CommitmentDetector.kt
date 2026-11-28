package io.github.daddymean.agentickeyboard.util

/**
 * Pure-JVM, local-only detector for actionable self-commitments in the user's
 * draft (the mirror image of [ReplyCompletenessCoach]).
 *
 * When a user types a concrete promise ("I'll send the deck by Friday",
 * "Let me check with Alex tomorrow", "Remind me to call the dentist"), the IME
 * can surface a quiet, dismissible chip to save it as a local note via
 * [ContextNotes]. Nothing is stored, logged, or sent anywhere unless the user
 * explicitly taps the chip.
 */
object CommitmentDetector {

    data class DetectedCommitment(
        /** Compact chip label shown in the IME feedback strip. */
        val chipLabel: String,
        /** Clean note text passed to `saveNote` only if the user taps the chip. */
        val noteText: String,
        /** Optional due-date/time phrase extracted from the clause (e.g. "by Friday"). */
        val dueHint: String?
    )

    const val MIN_CHARS = 14
    const val MAX_CHARS = 1_000
    private const val MAX_CHIP_SUMMARY_CHARS = 32
    private const val MAX_NOTE_CHARS = 180

    private val ACTION_VERBS = listOf(
        "pick up", "drop off", "follow up", "get back", "circle back", "reach out", "look into", "look up",
        "send", "email", "call", "text", "message", "ping", "share", "forward", "attach", "upload",
        "review", "check", "confirm", "book", "schedule", "reschedule", "cancel", "pay", "transfer",
        "submit", "file", "update", "fix", "draft", "write", "prepare", "finish", "complete",
        "deliver", "ship", "bring", "order", "buy", "renew", "ask", "reply"
    ).joinToString("|") { Regex.escape(it) }

    // Explicit first-person commitment lead-ins followed by a deliverable/follow-up verb.
    private val COMMITMENT_REGEX = Regex(
        pattern = """(?i)\b(?:i(?:'|’)?ll|i\s+will|let\s+me|i(?:'|’)?m\s+(?:going\s+to|gonna)|i\s+am\s+going\s+to|i\s+promise\s+to|i(?:'|’)?ll\s+make\s+sure\s+to|remind\s+me\s+to|don(?:'|’)?t\s+let\s+me\s+forget\s+to)\s+(?:just\s+|quickly\s+|definitely\s+)?(($ACTION_VERBS)\b[^.!?\n;]{2,140})"""
    )

    // Reject conditional / negated / hesitant lead-ins before the commitment phrase.
    private val NEGATION_OR_CONDITIONAL_PREFIX = Regex(
        pattern = """(?i)\b(?:if|whether|unless|maybe|might|not\s+sure\s+if|won(?:'|’)?t|will\s+not|never|don(?:'|’)?t\s+think)\b\s*$"""
    )

    private val NEGATION_INSIDE = Regex(
        pattern = """(?i)\b(?:never|not\s+going\s+to|won(?:'|’)?t|can(?:'|’)?t)\b"""
    )

    private val DUE_HINT_REGEX = Regex(
        pattern = """(?i)\b((?:by|before|on|until|this|next)\s+(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|week|month|morning|afternoon|evening|tonight|eod|end\s+of\s+(?:day|week))|tomorrow|tonight|later\s+today|in\s+\d+\s+(?:hours?|days?|weeks?)|at\s+\d{1,2}(?::\d{2})?\s*(?:am|pm)?)\b"""
    )

    private val CLAUSE_SPLIT = Regex("""(?<=[.!?\n;])\s+|\n+""")
    private val WHITESPACE = Regex("""\s+""")

    /**
     * The IME only offers a follow-up chip when notes can be saved and the
     * field is not sensitive (password, incognito, or other private editors)
     * or the playground. Detection itself is a pure function and stores nothing.
     */
    fun shouldOffer(
        isSensitiveField: Boolean,
        inPlaygroundMode: Boolean,
        canSaveNotes: Boolean
    ): Boolean = !isSensitiveField && !inPlaygroundMode && canSaveNotes

    /**
     * Returns the most recent actionable self-commitment in [draft], or null when
     * none is found (including blank text, slash commands, questions, or tiny fragments).
     */
    fun detect(draft: String): DetectedCommitment? {
        val trimmed = draft.trim()
        if (trimmed.length !in MIN_CHARS..MAX_CHARS) return null
        if (trimmed.startsWith("/")) return null

        val clauses = trimmed.split(CLAUSE_SPLIT)
        for (rawClause in clauses.asReversed()) {
            val clause = rawClause.trim()
            if (clause.length < MIN_CHARS) continue
            if (clause.endsWith("?")) continue

            val match = COMMITMENT_REGEX.find(clause) ?: continue
            val beforeMatch = clause.substring(0, match.range.first)
            if (NEGATION_OR_CONDITIONAL_PREFIX.containsMatchIn(beforeMatch)) continue

            val actionRaw = match.groupValues[1]
                .replace(WHITESPACE, " ")
                .trim()
                .trimEnd(',', ':', '-', ' ')
            if (NEGATION_INSIDE.containsMatchIn(actionRaw)) continue

            // Require at least an action verb + an object/target (minimum 2 tokens, e.g. "email Alex").
            val tokens = actionRaw.split(' ').filter { it.isNotEmpty() }
            if (tokens.size < 2) continue
            // Reject dangling articles/prepositions while the user is still mid-sentence
            // (e.g. "I'll send the", "Let me check with").
            if (tokens.last().lowercase() in DANGLING_TAIL_WORDS) continue

            val summary = actionRaw.replaceFirstChar { it.uppercaseChar() }
            val dueHint = DUE_HINT_REGEX.find(actionRaw)?.groupValues?.get(1)?.trim()
            val chipSummary = if (summary.length <= MAX_CHIP_SUMMARY_CHARS) {
                summary
            } else {
                summary.take(MAX_CHIP_SUMMARY_CHARS - 1).trimEnd() + "…"
            }
            val noteBody = if (summary.length <= MAX_NOTE_CHARS) {
                summary
            } else {
                summary.take(MAX_NOTE_CHARS - 1).trimEnd() + "…"
            }
            return DetectedCommitment(
                chipLabel = "📌 Save: $chipSummary",
                noteText = "Follow-up: $noteBody",
                dueHint = dueHint
            )
        }
        return null
    }

    private val DANGLING_TAIL_WORDS = setOf(
        "a", "an", "the", "to", "for", "with", "about", "by", "on", "in", "at", "my", "your", "our", "his", "her", "their"
    )
}
