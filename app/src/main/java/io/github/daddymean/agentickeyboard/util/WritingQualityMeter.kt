package io.github.daddymean.agentickeyboard.util

/**
 * Local writing-quality heuristics behind the tone meter: human-framed levels
 * for clarity, warmth, firmness and risk, plus a message-length label and one
 * plain-language note (e.g. "Clear but cold."). Pure JVM — no Android imports —
 * so unit tests exercise it directly, and it doubles as the offline fallback
 * when cloud tone analysis is unavailable.
 */
object WritingQualityMeter {

    const val RISK_LOW = "Low"
    const val RISK_MEDIUM = "Medium"
    const val RISK_HIGH = "High"

    data class Meter(
        val clarity: String,
        val warmth: String,
        val firmness: String,
        val risk: String,
        val lengthLabel: String,
        val note: String
    )

    private val WARM_WORDS = setOf(
        "thanks", "thank", "please", "appreciate", "grateful", "glad", "love",
        "hope", "happy", "welcome", "congrats", "congratulations", "wonderful",
        "great", "kindly"
    )
    private val HOSTILE_WORDS = setOf(
        "hate", "stupid", "idiot", "idiotic", "ridiculous", "useless", "pathetic",
        "worst", "terrible", "unacceptable", "furious", "disgusting", "incompetent",
        "damn", "hell", "wtf"
    )
    private val HOSTILE_PHRASES = listOf(
        "sick of", "fed up", "how dare", "screw you", "shut up", "your fault",
        "waste of my time", "don't you dare"
    )
    private val HEDGE_WORDS = setOf(
        "maybe", "perhaps", "possibly", "somewhat", "guess", "hopefully",
        "kinda", "sorta"
    )
    private val HEDGE_PHRASES = listOf(
        "i think", "i feel like", "sort of", "kind of", "if that's ok",
        "no worries if not", "just wondering"
    )
    private val FIRM_WORDS = setOf(
        "must", "need", "now", "immediately", "asap", "required", "deadline",
        "final", "won't", "cannot", "expect"
    )

    private val WORD_SPLIT = Regex("\\s+")
    private val SENTENCE_SPLIT = Regex("[.!?\\n]+")

    private fun words(text: String): List<String> =
        text.trim().split(WORD_SPLIT).filter { it.isNotBlank() }

    private fun tokenSet(text: String): Set<String> =
        words(text.lowercase())
            .map { word -> word.trim { c -> !c.isLetterOrDigit() && c != '\'' } }
            .filter { it.isNotBlank() }
            .toSet()

    /** Message-length label; always computed locally, never asked of the model. */
    fun lengthLabel(text: String): String {
        val count = words(text).size
        return when {
            count == 0 -> "Empty"
            count <= 3 -> "Very short"
            count <= 15 -> "Tight"
            count <= 40 -> "Medium"
            count <= 80 -> "Long for chat"
            else -> "Very long"
        }
    }

    /** Longest sentence drives readability: short sentences read clearer. */
    fun clarity(text: String): String {
        val sentenceLengths = text.split(SENTENCE_SPLIT).map { words(it).size }.filter { it > 0 }
        val longest = sentenceLengths.maxOrNull() ?: return "Clear"
        return when {
            longest > 30 -> "Dense"
            longest > 18 -> "OK"
            else -> "Clear"
        }
    }

    fun warmth(text: String): String {
        val tokens = tokenSet(text)
        return when {
            hostileHits(text.lowercase(), tokens) > 0 -> "Cold"
            tokens.any { it in WARM_WORDS } -> "Warm"
            else -> "Neutral"
        }
    }

    fun firmness(text: String): String {
        val tokens = tokenSet(text)
        val lower = text.lowercase()
        val hedges = tokens.count { it in HEDGE_WORDS } + HEDGE_PHRASES.count { it in lower }
        val firm = tokens.count { it in FIRM_WORDS } + text.count { it == '!' }
        return when {
            firm > hedges && firm > 0 -> "Firm"
            hedges > firm -> "Soft"
            else -> "Balanced"
        }
    }

    private fun hostileHits(lowercaseText: String, tokens: Set<String>): Int =
        tokens.count { it in HOSTILE_WORDS } + HOSTILE_PHRASES.count { it in lowercaseText }

    /**
     * How likely the message is to land badly: hostile wording weighs double,
     * shouting (mostly capitals, unless the words are warm — that's excitement)
     * and stacked "!!"/"??" escalate the level.
     */
    fun risk(text: String): String {
        val tokens = tokenSet(text)
        var score = hostileHits(text.lowercase(), tokens) * 2
        val letters = text.count { it.isLetter() }
        val shouting = letters >= 12 && text.count { it.isUpperCase() } * 10 >= letters * 7
        if (shouting && tokens.none { it in WARM_WORDS }) score += 3
        if ("!!" in text) score += 1
        if ("??" in text) score += 1
        return when {
            score >= 3 -> RISK_HIGH
            score >= 2 -> RISK_MEDIUM
            else -> RISK_LOW
        }
    }

    fun assess(text: String): Meter {
        val clarity = clarity(text)
        val warmth = warmth(text)
        val firmness = firmness(text)
        val risk = risk(text)
        val note = when {
            risk == RISK_HIGH -> "Could land badly — soften before sending."
            words(text).size > 80 -> "Too long for a quick message."
            clarity == "Dense" -> "Long sentences — split them up."
            warmth == "Cold" -> "Reads cold — a warmer opener would help."
            clarity == "Clear" && warmth == "Neutral" && firmness == "Firm" -> "Clear but blunt."
            firmness == "Soft" -> "Hedged — say it straight if you need action."
            else -> "Reads fine as-is."
        }
        return Meter(clarity, warmth, firmness, risk, lengthLabel(text), note)
    }
}
