package io.github.daddymean.agentickeyboard.util

import org.json.JSONObject

/**
 * Haggle mode: counter-offer *suggestions* from Flip's median sold prices.
 *
 * - The price cache is a small JSON file Keith imports himself (exported from Flip on his own
 *   machine). It is stored in app-private prefs; nothing is fetched from the network.
 * - Input is only the text Keith is typing in the current field, so haggle mode does not read
 *   other apps' screens.
 * - Output is a list of suggested numbers. The keyboard shows them; it never inserts or sends
 *   anything, and Keith types or taps the counter himself.
 * - Off by default ([KeyboardSettings.isHaggleModeEnabled]) and refused in sensitive editors.
 *
 * Cache format (version 1):
 * {"version":1,"generated":"2026-11-02","items":[
 *   {"key":"nintendo switch oled","aliases":["switch oled"],"median_sold_cents":21000,"n":14}]}
 */
object HaggleAdvisor {

    data class Comp(val key: String, val aliases: List<String>, val medianSoldCents: Long, val sampleSize: Int)

    data class Advice(
        val comp: Comp,
        val askingCents: Long,
        /** Opening counter, a fair middle, and a walk-away ceiling. */
        val counters: List<Long>,
        val summary: String,
    )

    const val MIN_SAMPLE = 5
    private val PRICE = Regex("\\$\\s?(\\d{1,3}(?:,\\d{3})*|\\d+)(?:\\.(\\d{2}))?")

    fun parseCache(json: String): List<Comp> {
        if (json.isBlank()) return emptyList()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
        if (root.optInt("version") != 1) return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        return (0 until minOf(items.length(), 5_000)).mapNotNull { i ->
            val o = items.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key").trim().lowercase()
            val median = o.optLong("median_sold_cents", -1)
            if (key.length < 3 || median <= 0) return@mapNotNull null
            val aliases = o.optJSONArray("aliases")?.let { a -> (0 until a.length()).map { a.optString(it).trim().lowercase() } }.orEmpty()
            Comp(key, aliases.filter { it.length >= 3 }, median, o.optInt("n", 0))
        }
    }

    /** The first dollar amount in [text], in cents. */
    fun parseAskingCents(text: String): Long? {
        val m = PRICE.find(text) ?: return null
        val dollars = m.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        val cents = m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0
        return dollars * 100 + cents
    }

    /** Longest key or alias contained in [text] wins, so "switch oled" beats "switch". */
    fun match(text: String, comps: List<Comp>): Comp? {
        val t = text.lowercase()
        return comps.flatMap { c -> (listOf(c.key) + c.aliases).map { it to c } }
            .filter { (name, _) -> Regex("\\b" + Regex.escape(name) + "\\b").containsMatchIn(t) }
            .maxByOrNull { it.first.length }?.second
    }

    fun advise(text: String, comps: List<Comp>): Advice? {
        val comp = match(text, comps) ?: return null
        if (comp.sampleSize < MIN_SAMPLE) return null
        val ask = parseAskingCents(text) ?: return null
        if (ask <= 0) return null
        val median = comp.medianSoldCents
        // Buying: open ~25% under the lower of ask and median, meet near 90% of it, never above it.
        val anchor = minOf(ask, median)
        val counters = listOf(roundDollars(anchor * 75 / 100), roundDollars(anchor * 90 / 100), roundDollars(anchor))
            .filter { it in 1 until ask }.distinct()
        if (counters.isEmpty()) return null
        val vs = when {
            ask > median * 110 / 100 -> "above"
            ask < median * 90 / 100 -> "below"
            else -> "near"
        }
        return Advice(comp, ask, counters, "Ask ${money(ask)} is $vs Flip median ${money(median)} (${comp.sampleSize} sold)")
    }

    private fun roundDollars(cents: Long): Long = ((cents + 50) / 100) * 100
    fun money(cents: Long): String = "$" + (cents / 100) + if (cents % 100 != 0L) ".%02d".format(cents % 100) else ""
}
