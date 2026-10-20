package io.github.daddymean.agentickeyboard.twin.memory

/**
 * KEYBOARD-035: the twin never makes a new decision about money, inheritance or legal
 * matters, and never gives permission for one on Dad's behalf. Someone could try to use a
 * dead parent's voice to settle a family argument or to pressure a sibling.
 *
 * - A question on one of these topics gets [REFUSAL]. It points to the real documents and
 *   the guardians, and quotes nothing, even when memories match.
 * - In model output, a sentence on one of these topics survives [CitationGuard] only if it is
 *   word for word what a cited source says ([needsVerbatim]). A paraphrase like "Dad wanted
 *   you to have the house" can't slip through on the strength of an unrelated citation.
 */
object DecisionGuard {
    const val REFUSAL = "I can't make or change decisions about money, inheritance or legal matters, and I can't give " +
        "permission for them on Dad's behalf. Those answers come from Dad's real documents (his will and estate papers) " +
        "and from the guardians: Guardian 1, Guardian 2 and Guardian 3."

    enum class Topic { MONEY, INHERITANCE, LEGAL }

    private val PATTERNS: Map<Topic, Regex> = mapOf(
        Topic.MONEY to Regex(
            "\\b(money|cash|dollars?|bucks|pay(ing)? (for|off|me|back)|loan|lend|borrow|debt|savings|bank( account)?|invest\\w*|" +
                "stocks?|shares in|crypto\\w*|bitcoin|401 ?k|ira|pension|credit card|college fund|wire|venmo|paypal)\\b|[$€£]\\s*\\d"
        ),
        Topic.INHERITANCE to Regex(
            "\\b(inherit\\w*|estate|executor|beneficiar\\w*|heirlooms?|trust fund|probate|who (gets|should get|inherits)|" +
                "should (i|we|they|he|she) get|leave (me|us|her|him|them|it to)|left (me|us|her|him|them)|" +
                "(sell|keep|split|divide|have|get|take|own) (the|his|dad's|your|my) (house|home|cabin|property|car|land|ring|watch|money)|" +
                "(sell|keep|split|divide) (the|his|dad's|your) (house|home|cabin|property|car|land|things|stuff|ring|watch)|" +
                "(the|his|dad's|your|a) will\\b)"
        ),
        Topic.LEGAL to Regex(
            "\\b(legal\\w*|lawyer|attorney|court|sue|suing|lawsuit|custody|guardianship|power of attorney|contract|" +
                "notar\\w*|deed|taxes|irs|sign (the|this|these|his) (papers?|documents?|forms?))\\b"
        )
    )

    fun topics(text: String): Set<Topic> {
        val t = text.lowercase().replace('’', '\'')
        return PATTERNS.filterValues { it.containsMatchIn(t) }.keys
    }

    fun isDecisionTopic(text: String) = topics(text).isNotEmpty()

    /** The refusal for [question], or null when it may be answered from memories. */
    fun check(question: String): CitedReply? =
        if (isDecisionTopic(question)) CitedReply(listOf(ReplySegment(REFUSAL)), emptyList(), grounded = false) else null

    /** A decision-topic sentence must be a verbatim span of one of its sources. */
    fun needsVerbatim(segment: String) = isDecisionTopic(segment)
}

/**
 * KEYBOARD-035 (OWASP LLM01 prompt injection, LLM07 system prompt leakage): memory text and
 * questions are data. Before they go into a model context, anything shaped like a citation
 * marker or a role tag is removed, so a memory can't forge `[core:who_i_am]` or claim to be the
 * rules.
 */
object ContextSanitizer {
    private val BRACKETS = Regex("\\[[^\\]\\n]{0,64}\\]")
    private val ROLE = Regex("(?i)\\b(system|assistant|user|developer)\\s*:")
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]")

    fun clean(text: String): String =
        ROLE.replace(BRACKETS.replace(CONTROL.replace(text, ""), ""), "").replace(Regex("[ \\t]{2,}"), " ").trim()
}
