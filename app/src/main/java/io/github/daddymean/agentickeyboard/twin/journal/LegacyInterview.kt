package io.github.daddymean.agentickeyboard.twin.journal

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A question one of the kids asked Keith, kept as a sealed journal event. Withdrawing it deletes the record. */
data class KidQuestion(val recordId: Long, val kid: String, val text: String, val askedAtMillis: Long, val answered: Boolean) {
    val promptId: String get() = LegacyInterview.KID_PREFIX + recordId
}

/** This week's interview: the prompt ids fixed when the week's session was first opened. */
data class InterviewSession(val week: LocalDate, val promptIds: List<String>)

/**
 * KEYBOARD-034: a weekly legacy interview of 6–8 questions, plus a queue of the kids' own
 * questions for Keith to answer.
 *
 * - The questions (`assets/twin/legacy-interview.json`) are written for this app. They are
 *   only inspired by the topics of StoryCorps Great Questions, because StoryCorps publishes
 *   no open licence and its Terms of Use bar copying its list.
 * - A week runs Monday to Sunday. The first time Keith opens it that week, the session is fixed
 *   and saved as a journal event, so answering doesn't reshuffle it.
 * - Kids' questions come first, oldest first, up to [MAX]. The rest is filled with unanswered
 *   interview questions to reach at least [MIN]: one per topic, starting with the topics
 *   answered least, with at most one heavy question per session.
 * - Answers are ordinary journal answers (`promptId` `LI-…` or `KQ-<record id>`, `child` set to
 *   the kid who asked). So, like every answer, they reach the twin only after Keith approves them.
 * - Nothing nudges anyone: no notifications. The journal home shows the week's progress.
 */
object LegacyInterview {
    const val ASSET = "twin/legacy-interview.json"
    const val MIN = 6
    const val MAX = 8
    const val KID_PREFIX = "KQ-"
    const val MAX_QUESTION_CHARS = 500
    const val MAX_QUEUED_PER_KID = 20
    const val EVENT_KID_QUESTION = "kid_question"
    const val EVENT_SESSION = "interview_session"
    val KIDS = (1..6).map { "Kid $it" }

    fun weekOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    fun kidQuestions(records: List<JournalRecord>): List<KidQuestion> {
        val answered = answeredIds(records)
        return records.filter { it.kind == JournalKinds.EVENT && it.body.optString("type") == EVENT_KID_QUESTION }
            .map { r -> KidQuestion(r.id, r.body.optString("kid"), r.body.optString("text"), r.atMillis, (KID_PREFIX + r.id) in answered) }
            .sortedBy { it.askedAtMillis }
    }

    /** The body of a new kid-question event. Throws with a readable message if it isn't acceptable. */
    fun newKidQuestion(records: List<JournalRecord>, kid: String, text: String): JSONObject {
        val t = text.trim()
        require(kid in KIDS) { "Pick who is asking." }
        require(t.length >= 3) { "Write a question first." }
        require(t.length <= MAX_QUESTION_CHARS) { "Please keep it under $MAX_QUESTION_CHARS characters." }
        require(kidQuestions(records).count { it.kid == kid && !it.answered } < MAX_QUEUED_PER_KID) { "$kid already has $MAX_QUEUED_PER_KID questions waiting." }
        return JSONObject().put("type", EVENT_KID_QUESTION).put("kid", kid).put("text", t)
    }

    /** A kid's question as a prompt the journal's answer screen can use. */
    fun prompt(q: KidQuestion): Prompt = Prompt(
        id = q.promptId, category = "kids_questions", categoryLabel = "Question from ${q.kid}", text = q.text,
        followUps = emptyList(), depth = 2, answerMode = "either", emotionallyHeavy = false, perChild = false,
        placeholders = emptyList(), revisit = Revisit.of("none"), estMinutes = 5
    )

    fun answeredIds(records: List<JournalRecord>): Set<String> =
        records.filter { it.kind == JournalKinds.ANSWER && !it.body.optBoolean("followUp", false) }.map { it.body.optString("promptId") }.toSet()

    fun savedSession(records: List<JournalRecord>, week: LocalDate): InterviewSession? =
        records.lastOrNull { it.kind == JournalKinds.EVENT && it.body.optString("type") == EVENT_SESSION && it.body.optString("week") == week.toString() }
            ?.let { r -> InterviewSession(week, r.body.optJSONArray("prompts").strings()) }

    fun sessionEvent(s: InterviewSession): JSONObject =
        JSONObject().put("type", EVENT_SESSION).put("week", s.week.toString()).put("prompts", JSONArray(s.promptIds))

    /** Picks a new session for the week of [today]. Deterministic for a given week and history. */
    fun plan(bank: List<Prompt>, records: List<JournalRecord>, today: LocalDate): InterviewSession {
        val week = weekOf(today)
        val answered = answeredIds(records)
        val earlier = records.filter { it.kind == JournalKinds.EVENT && it.body.optString("type") == EVENT_SESSION && it.body.optString("week") != week.toString() }
            .flatMap { it.body.optJSONArray("prompts").strings() }.toSet()
        val chosen = kidQuestions(records).filter { !it.answered }.take(MAX).map { it.promptId }.toMutableList()
        if (chosen.size < MIN) {
            val perCategory = bank.associate { p -> p.category to bank.count { it.category == p.category && it.id in answered } }
            // Unanswered first, then not-yet-offered; categories by fewest answers; a stable shuffle per week.
            val pool = bank.filter { it.id !in answered }
                .sortedWith(compareBy<Prompt>({ it.id in earlier }, { perCategory[it.category] ?: 0 }, { (it.id + week).hashCode() }))
            val usedCategories = HashSet<String>()
            var heavy = false
            for (pass in 0..1) for (p in pool) {
                if (chosen.size >= MIN) break
                if (p.id in chosen) continue
                if (pass == 0 && p.category in usedCategories) continue
                if (p.heavy && heavy) continue
                chosen += p.id; usedCategories += p.category; heavy = heavy || p.heavy
            }
        }
        return InterviewSession(week, chosen)
    }

    fun date(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
