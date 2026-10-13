package io.github.daddymean.agentickeyboard.twin.journal

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Record kinds in [JournalStore]. */
object JournalKinds {
    const val ANSWER = "answer"
    const val INSTRUMENT = "instrument"
    const val CARD_SORT = "card_sort"
    const val VOICE_SCRIPT = "voice_script"
    const val EVENT = "event"
    const val SETTINGS = "settings"
}

/** Journal settings, stored encrypted (children's names are personal). */
data class JournalSettings(
    val children: List<String> = emptyList(),
    val partner: String? = null,
    val morningNudge: Boolean = false,
    val heavyPausedUntil: LocalDate? = null,
    val startDate: LocalDate? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("children", JSONArray(children))
        .put("partner", partner ?: "")
        .put("morningNudge", morningNudge)
        .put("heavyPausedUntil", heavyPausedUntil?.toString() ?: "")
        .put("startDate", startDate?.toString() ?: "")

    companion object {
        fun fromJson(o: JSONObject) = JournalSettings(
            children = o.optJSONArray("children").strings().map(String::trim).filter(String::isNotEmpty),
            partner = o.optString("partner").takeIf { it.isNotBlank() },
            morningNudge = o.optBoolean("morningNudge", false),
            heavyPausedUntil = o.optString("heavyPausedUntil").takeIf { it.isNotBlank() }?.let(LocalDate::parse),
            startDate = o.optString("startDate").takeIf { it.isNotBlank() }?.let(LocalDate::parse)
        )

        /** "Ada, Ben ,  Cy" -> [Ada, Ben, Cy]; duplicates removed. */
        fun parseNames(s: String): List<String> = s.split(',', '\n').map(String::trim).filter(String::isNotEmpty).distinct()
    }
}

/** Rebuilds the scheduler state and instrument responses from the decrypted records. */
object JournalHistory {
    fun date(atMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()

    fun settings(records: List<JournalRecord>): JournalSettings =
        records.lastOrNull { it.kind == JournalKinds.SETTINGS }?.let { JournalSettings.fromJson(it.body) } ?: JournalSettings()

    fun state(records: List<JournalRecord>, bank: List<Prompt>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): JournalState {
        val s = settings(records)
        val byId = bank.associateBy { it.id }
        val answers = records.filter { it.kind == JournalKinds.ANSWER && !it.body.optBoolean("followUp", false) }.mapNotNull { r ->
            val p = byId[r.body.optString("promptId")] ?: return@mapNotNull null
            AnsweredPrompt(p.id, r.body.optString("child").takeIf { it.isNotEmpty() }, date(r.atMillis, zone), p.category, p.heavy)
        }
        val snoozed = HashMap<String, LocalDate>()
        val retired = HashSet<String>()
        val skips = ArrayList<LocalDate>()
        for (r in records.filter { it.kind == JournalKinds.EVENT }) {
            val key = r.body.optString("key")
            val d = date(r.atMillis, zone)
            when (r.body.optString("type")) {
                "skip" -> { snoozed[key] = d.plusDays(JournalScheduler.SNOOZE_DAYS); skips += d }
                "retire" -> retired += key
                "unretire" -> retired -= key
            }
        }
        // An answer after a skip clears the auto-lighten streak.
        val lastAnswerDate = answers.maxOfOrNull { it.date }
        val recentSkips = if (lastAnswerDate == null) skips else skips.filter { it > lastAnswerDate }
        val start = s.startDate ?: records.firstOrNull()?.let { date(it.atMillis, zone) } ?: today
        return JournalState(
            startDate = start,
            children = s.children,
            partner = s.partner,
            morningNudge = s.morningNudge,
            answers = answers,
            snoozedUntil = snoozed,
            retired = retired,
            skipDates = recentSkips,
            heavyPausedUntil = s.heavyPausedUntil
        )
    }

    /** Latest response per item for an instrument, across every saved block. */
    fun responses(records: List<JournalRecord>, instrumentId: String): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        for (r in records) {
            if (r.kind != JournalKinds.INSTRUMENT || r.body.optString("instrumentId") != instrumentId) continue
            val resp = r.body.optJSONObject("responses") ?: continue
            for (k in resp.keys()) out[k.toInt()] = resp.getInt(k)
        }
        return out
    }

    fun answerCount(records: List<JournalRecord>) = records.count { it.kind == JournalKinds.ANSWER || it.kind == JournalKinds.VOICE_SCRIPT }
}
