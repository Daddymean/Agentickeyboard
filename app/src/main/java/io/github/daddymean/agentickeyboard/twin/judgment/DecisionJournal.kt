package io.github.daddymean.agentickeyboard.twin.judgment

import org.json.JSONArray
import org.json.JSONObject

/**
 * Twin idea (Nikola, Nov 2; Keith approved): on a big choice Keith gives a short "why",
 * what he expects and how sure he is. At [Decision.reviewAtMillis] the twin asks how it
 * turned out; [brier] grades his forecasts, like the radar grading. The reasons become
 * twin context only after Keith records them himself. On-device only, pure logic.
 */
data class Decision(
    val id: String, val title: String, val why: String, val expected: String,
    val confidence: Double, val madeAtMillis: Long, val reviewAtMillis: Long,
    val outcome: String? = null, val turnedOutAsExpected: Boolean? = null
) {
    init { require(confidence in 0.0..1.0) { "confidence must be 0..1" } }
    val reviewed: Boolean get() = turnedOutAsExpected != null
}

class DecisionJournal(val decisions: List<Decision> = emptyList()) {
    fun add(d: Decision): DecisionJournal {
        require(d.title.isNotBlank() && d.why.isNotBlank()) { "A decision needs a title and a why." }
        require(decisions.none { it.id == d.id }) { "duplicate id" }
        return DecisionJournal(decisions + d)
    }

    /** Unreviewed decisions whose review date has come, oldest first. */
    fun due(now: Long): List<Decision> = decisions.filter { !it.reviewed && it.reviewAtMillis <= now }.sortedBy { it.reviewAtMillis }

    fun recordOutcome(id: String, outcome: String, asExpected: Boolean): DecisionJournal =
        DecisionJournal(decisions.map { if (it.id == id) it.copy(outcome = outcome.trim(), turnedOutAsExpected = asExpected) else it })

    /** Mean Brier score over reviewed decisions (0 = perfect, 0.25 = coin flip), or null if none. */
    fun brier(): Double? {
        val r = decisions.filter { it.reviewed }
        if (r.isEmpty()) return null
        return r.sumOf { val o = if (it.turnedOutAsExpected == true) 1.0 else 0.0; (it.confidence - o) * (it.confidence - o) } / r.size
    }

    /** Keith's own reasons, for the values card draft. */
    fun whys(limit: Int = 8): List<String> = decisions.sortedByDescending { it.madeAtMillis }.map { it.why.trim() }.distinct().take(limit)

    fun toJson(): JSONObject = JSONObject().put("v", 1).put("decisions", JSONArray(decisions.map {
        JSONObject().put("id", it.id).put("title", it.title).put("why", it.why).put("expected", it.expected)
            .put("confidence", it.confidence).put("madeAt", it.madeAtMillis).put("reviewAt", it.reviewAtMillis)
            .put("outcome", it.outcome ?: JSONObject.NULL).put("asExpected", it.turnedOutAsExpected ?: JSONObject.NULL)
    }))

    companion object {
        fun fromJson(o: JSONObject): DecisionJournal {
            val a = o.getJSONArray("decisions")
            return DecisionJournal((0 until a.length()).map { i ->
                val d = a.getJSONObject(i)
                Decision(d.getString("id"), d.getString("title"), d.getString("why"), d.getString("expected"), d.getDouble("confidence"),
                    d.getLong("madeAt"), d.getLong("reviewAt"),
                    if (d.isNull("outcome")) null else d.getString("outcome"), if (d.isNull("asExpected")) null else d.getBoolean("asExpected"))
            })
        }
    }
}
