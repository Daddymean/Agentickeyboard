package io.github.daddymean.agentickeyboard.twin.judgment

import org.json.JSONArray
import org.json.JSONObject

/**
 * Twin idea (Nikola, Nov 2; Keith approved): a one-page "how Keith decides" card. The
 * twin drafts it from Keith's own approved words; Keith edits and approves it. Only an
 * approved card is ever used as the twin's anchor ([anchor]); a draft never is, and any
 * edit by the twin side ([redraft]) drops the approval until Keith approves again.
 */
data class ValuesCard(val sections: List<Section>, val approvedAtMillis: Long? = null, val updatedAtMillis: Long) {
    data class Section(val title: String, val lines: List<String>)

    val approved: Boolean get() = approvedAtMillis != null

    /** Keith's edit is his approval. */
    fun approveWithEdits(sections: List<Section>, now: Long) = copy(sections = clean(sections), approvedAtMillis = now, updatedAtMillis = now)

    /** A machine-made change; Keith must approve again. */
    fun redraft(sections: List<Section>, now: Long) = copy(sections = clean(sections), approvedAtMillis = null, updatedAtMillis = now)

    /** The anchor text for the twin, or null if Keith hasn't approved this card. */
    fun anchor(): String? = if (!approved) null else
        "How Keith decides (written and approved by Keith):\n" + sections.filter { it.lines.isNotEmpty() }
            .joinToString("\n") { s -> s.title + ":\n" + s.lines.joinToString("\n") { "- $it" } }

    fun toJson(): JSONObject = JSONObject().put("v", 1).put("approvedAt", approvedAtMillis ?: -1L).put("updatedAt", updatedAtMillis)
        .put("sections", JSONArray(sections.map { JSONObject().put("title", it.title).put("lines", JSONArray(it.lines)) }))

    companion object {
        val TITLES = listOf("What matters most", "How I decide", "Lines I won't cross", "How I talk")
        const val MAX_LINES = 8

        private fun clean(s: List<Section>) = s.map { it.copy(lines = it.lines.map(String::trim).filter(String::isNotEmpty).distinct().take(MAX_LINES)) }

        /**
         * Draft from Keith's own approved text only: core memory values, plus the phrases he
         * keeps removing (as "Don't say" lines). Never approved.
         */
        fun draft(coreValues: List<String>, decisionWhys: List<String>, log: DisagreementLog, now: Long): ValuesCard {
            val talk = log.avoidPhrases().take(4).map { "Don't say \"$it\"" }
            return ValuesCard(clean(listOf(
                Section(TITLES[0], coreValues),
                Section(TITLES[1], decisionWhys),
                Section(TITLES[2], emptyList()),
                Section(TITLES[3], talk)
            )), approvedAtMillis = null, updatedAtMillis = now)
        }

        fun fromJson(o: JSONObject): ValuesCard {
            val a = o.getJSONArray("sections")
            return ValuesCard((0 until a.length()).map { i ->
                val s = a.getJSONObject(i); val l = s.getJSONArray("lines")
                Section(s.getString("title"), (0 until l.length()).map(l::getString))
            }, o.optLong("approvedAt", -1L).takeIf { it >= 0 }, o.getLong("updatedAt"))
        }
    }
}
