package io.github.daddymean.agentickeyboard.twin.judgment

/**
 * Twin idea (Nikola, Nov 2; Keith approved), rule-based only: once a month compare the
 * twin's recent replies with Keith's own recent writing and flag drift into a generic
 * assistant voice. No model, no network: simple counts on text already on the phone.
 */
object DriftCheck {
    const val INTERVAL_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val MIN_SAMPLES = 5

    /** Phrases that sound like a generic assistant, not Keith. */
    val GENERIC = listOf(
        "as an ai", "i'd be happy to", "i would be happy to", "great question", "certainly!", "i hope this helps",
        "feel free to", "let me know if you", "it's important to note", "in conclusion", "absolutely!", "delve"
    )

    data class Style(val avgWords: Double, val genericPer100: Double, val exclaimPer100: Double, val contractionRate: Double)
    data class Result(val twin: Style, val keith: Style, val reasons: List<String>) { val drifting get() = reasons.isNotEmpty() }

    fun due(lastRunMillis: Long?, now: Long) = lastRunMillis == null || now - lastRunMillis >= INTERVAL_MILLIS

    fun style(texts: List<String>): Style {
        val words = texts.flatMap { it.split(Regex("\\s+")).filter(String::isNotBlank) }
        val n = words.size.coerceAtLeast(1)
        val low = texts.joinToString("\n").lowercase()
        val generic = GENERIC.sumOf { Regex(Regex.escape(it)).findAll(low).count() }
        val contractions = words.count { it.contains('\'') || it.contains('’') }
        return Style(words.size.toDouble() / texts.size.coerceAtLeast(1), generic * 100.0 / n, low.count { it == '!' } * 100.0 / n, contractions.toDouble() / n)
    }

    /** Null if there aren't [MIN_SAMPLES] of each to compare. */
    fun compare(twinReplies: List<String>, keithWriting: List<String>): Result? {
        if (twinReplies.size < MIN_SAMPLES || keithWriting.size < MIN_SAMPLES) return null
        val t = style(twinReplies); val k = style(keithWriting)
        val reasons = buildList {
            if (t.genericPer100 > k.genericPer100 + 0.5) add("Uses generic assistant phrases Keith doesn't.")
            if (k.avgWords > 0 && t.avgWords > k.avgWords * 1.6) add("Replies are much longer than Keith writes.")
            if (k.contractionRate > 0.02 && t.contractionRate < k.contractionRate * 0.4) add("Sounds more formal than Keith (few contractions).")
            if (t.exclaimPer100 > k.exclaimPer100 + 2.0) add("Far more exclamation marks than Keith uses.")
        }
        return Result(t, k, reasons)
    }
}
