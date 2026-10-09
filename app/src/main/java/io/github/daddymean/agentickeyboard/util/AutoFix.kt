package io.github.daddymean.agentickeyboard.util

/**
 * KEYBOARD-011: high-confidence auto-fix on space (Keith, 2026-10-08 21:24 PT:
 * on by default, ⌫ undoes, with an off switch in Settings).
 *
 * [fix] holds the guards; [LocalSpelling.autoFixCandidate] holds the confidence
 * rule. A word is left exactly as typed when it:
 * - is a dictionary word, common slang, or one of the user's own words;
 * - contains a digit, `@`, `#`, `/`, `:`, `_`, `.` or `-` inside it (URLs, emails,
 *   handles, numbers);
 * - is ALL CAPS, or has a capital after its first letter;
 * - starts with a capital in the middle of a sentence (probably a name);
 * - has no single candidate that clearly beats the next one.
 * Sensitive and incognito fields, paused corrections and the Settings switch are
 * checked by the caller before this runs.
 */
object AutoFix {
    /** Score lead the best candidate needs over the runner-up. */
    const val MARGIN = 0.8

    /** Largest edit cost for a word that looks built from known parts. */
    const val BUILT_WORD_MAX_COST = 0.6

    /** Largest edit cost that may be fixed without a tap. */
    fun maxCost(length: Int): Double = when {
        length <= 3 -> 0.6
        length <= 5 -> 1.2
        else -> 1.7
    }

    private const val LEADING = "(\"'“‘"
    private val SENTENCE_END = setOf('.', '!', '?', '\n')
    private val BLOCKED = setOf('@', '#', '/', ':', '_', '.', '-', '\\', '=', '&', '+', '%', '$', '*', '~', '|', '<', '>')

    /**
     * What [word] (as typed, possibly with punctuation) should become on space, or
     * null to leave it. [before] is the text before the word.
     */
    fun fix(
        word: String,
        before: String,
        spelling: LocalSpelling,
        personalWords: Set<String> = emptySet(),
        tapsFor: ((String) -> List<TapOffset>?)? = null,
        margin: Double = MARGIN
    ): String? {
        val (withLead, trailing) = LearnedRuleFilter.splitTrailingPunctuation(word)
        val lead = withLead.takeWhile { it in LEADING }
        val core = withLead.substring(lead.length)
        if (core.length < 2 || core.any { it.isDigit() || it in BLOCKED }) return null
        if (!core.all { it.isLetter() }) return null
        if (core.drop(1).any { it.isUpperCase() }) return null
        val capitalised = core[0].isUpperCase()
        if (capitalised && isMidSentence(before + lead)) return null
        val lower = core.lowercase()
        if (lower in personalWords || spelling.isKnownWord(lower)) return null
        val fixed = spelling.autoFixCandidate(lower, tapsFor?.invoke(core), margin) ?: return null
        val cased = if (capitalised) fixed.replaceFirstChar { it.uppercase() } else fixed
        return lead + cased + trailing
    }

    /** True when the text so far does not end a sentence (so a capital means a name). */
    fun isMidSentence(before: String): Boolean {
        val prev = before.trimEnd { it == ' ' || it == '\t' }.trimEnd { it in "\"'”’)" }
        return prev.isNotEmpty() && prev.last() !in SENTENCE_END
    }
}
