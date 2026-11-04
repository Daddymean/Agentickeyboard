package io.github.daddymean.agentickeyboard.twin.judgment

import org.json.JSONArray
import org.json.JSONObject

/**
 * Twin idea (Nikola, Nov 2; Keith approved): when Keith corrects or overrides a twin
 * reply, keep the pair as a "that's not me" example. Phrases the twin keeps using
 * that Keith keeps taking out become [avoidPhrases], used to re-weight later replies.
 *
 * On-device only: callers store [toJson] in the encrypted twin journal, never send it.
 * Pure logic, no Android.
 */
data class Correction(val twinText: String, val keithText: String, val context: String?, val atMillis: Long)

class DisagreementLog(val corrections: List<Correction> = emptyList()) {

    /** Adds a correction. Empty edits and "corrections" that change nothing are ignored. */
    fun add(c: Correction): DisagreementLog {
        if (c.twinText.isBlank() || c.keithText.isBlank()) return this
        if (norm(c.twinText) == norm(c.keithText)) return this
        return DisagreementLog((corrections + c).takeLast(MAX))
    }

    /** Newest first, for the twin's "not me" examples. */
    fun notMeExamples(limit: Int = 5): List<Correction> = corrections.sortedByDescending { it.atMillis }.take(limit)

    /** Word pairs (bigrams) Keith removed in at least [minCount] separate corrections. */
    fun avoidPhrases(minCount: Int = 2): List<String> {
        val counts = HashMap<String, Int>()
        for (c in corrections) {
            val removed = bigrams(c.twinText) - bigrams(c.keithText)
            removed.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        return counts.filter { it.value >= minCount }.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
    }

    /** Lower is better: how many avoid-phrases a candidate reply uses. */
    fun penalty(candidate: String, minCount: Int = 2): Int = avoidPhrases(minCount).toSet().intersect(bigrams(candidate)).size

    fun toJson(): JSONObject = JSONObject().put("v", 1).put("corrections", JSONArray(corrections.map {
        JSONObject().put("twin", it.twinText).put("keith", it.keithText).put("context", it.context ?: "").put("at", it.atMillis)
    }))

    companion object {
        const val MAX = 500
        fun fromJson(o: JSONObject): DisagreementLog {
            val a = o.optJSONArray("corrections") ?: JSONArray()
            return DisagreementLog((0 until a.length()).map { i ->
                val c = a.getJSONObject(i)
                Correction(c.getString("twin"), c.getString("keith"), c.optString("context").ifBlank { null }, c.getLong("at"))
            })
        }
        internal fun words(s: String) = norm(s).split(' ').filter { it.isNotEmpty() }
        internal fun bigrams(s: String): Set<String> = words(s).zipWithNext { a, b -> "$a $b" }.toSet()
        private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}' ]+"), " ").replace(Regex("\\s+"), " ").trim()
    }
}
