package io.github.daddymean.agentickeyboard.twin.journal

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.random.Random

/** A prompt as shown today: for one child where it is per-child, with names filled in. */
data class PromptInstance(
    val prompt: Prompt,
    val child: String?,
    val text: String,
    val isRevisit: Boolean,
    val slot: Slot
) {
    enum class Slot { MORNING, EVENING }
    /** Identifies "this prompt for this child" in answers, skips and retirements. */
    val key: String get() = keyOf(prompt.id, child)

    companion object {
        fun keyOf(promptId: String, child: String?) = if (child == null) promptId else "$promptId#$child"
    }
}

/** What the scheduler needs to know about one past answer. */
data class AnsweredPrompt(val promptId: String, val child: String?, val date: LocalDate, val category: String, val heavy: Boolean) {
    val key: String get() = PromptInstance.keyOf(promptId, child)
}

/** Settings and history that shape the plan. */
data class JournalState(
    val startDate: LocalDate,
    val children: List<String> = emptyList(),
    val partner: String? = null,
    val morningNudge: Boolean = false,
    val answers: List<AnsweredPrompt> = emptyList(),
    /** Prompt key -> first date it may come back ("Skip for now"). */
    val snoozedUntil: Map<String, LocalDate> = emptyMap(),
    /** Prompt keys retired with "Not this one". */
    val retired: Set<String> = emptySet(),
    /** Dates on which a shown prompt was skipped, newest last (for auto-lighten). */
    val skipDates: List<LocalDate> = emptyList(),
    /** "Life is heavy right now": no heavy prompts until this date. */
    val heavyPausedUntil: LocalDate? = null
)

data class DailyPlan(val date: LocalDate, val prompts: List<PromptInstance>, val note: String?)

/**
 * KEYBOARD-025: picks today's 1–2 prompts following `schedule.md`.
 *
 * - **One evening prompt a day**, plus an optional depth-1 **morning nudge**.
 * - **Phases** by week since the start: setup boundaries (week 1); warm-up categories
 *   (weeks 2–5); the story (6–14); the heart (15–26); for the kids (27–36); gap-fill
 *   (37+). About 10% of the weight goes to other phases' items.
 * - **Deep days** are Wednesday and Saturday (at most 2 a week, never back to back, never
 *   Sunday night). Heavy items (emotionally heavy or depth 3) come only on deep-day
 *   evenings, never after 9 p.m., **never two in a row**, at most **3 in any 7 days**,
 *   and not while "life is heavy right now" is on. After a heavy answer the next prompt
 *   is light (depth 1, humor or tastes preferred).
 * - **No category two days running**; categories unseen for 14+ days get extra weight.
 * - **Per-child prompts** are asked once per child named in settings, favouring the
 *   child with the fewest answers; "[child name]" and "[partner name]" are filled in.
 * - **Yearly revisits**: items marked yearly come back about 12 months after the last
 *   answer, ahead of new material.
 * - **Skip for now** returns a prompt no sooner than 14 days later; **Not this one**
 *   retires it. Two skips in a row lighten the next prompts to depth 1.
 * The plan is deterministic for a given day and state, so it doesn't change on reopen.
 */
object JournalScheduler {
    const val SNOOZE_DAYS = 14L
    const val HEAVY_CUTOFF_HOUR = 21
    const val MAX_HEAVY_PER_7_DAYS = 3
    const val REVISIT_DAYS = 365L

    private val setupPrompts = setOf("BD-001", "BD-002", "BD-003", "BD-012")
    private val phases = listOf(
        2..5 to setOf("childhood", "tastes", "habits", "humor", "phrasing", "life_story"),
        6..14 to setOf("life_story", "work", "people", "family_history", "opinions"),
        15..26 to setOf("parenting", "love", "values", "turning_points", "conflict_stress", "pride_regret", "opinions"),
        27..36 to setOf("messages", "hidden_self", "remembered", "parenting")
    )
    private val lightCategories = setOf("humor", "tastes")

    /** The most recent answer: latest date, and on that date the one recorded last. */
    private fun lastOf(state: JournalState): AnsweredPrompt? =
        state.answers.withIndex().maxWithOrNull(compareBy({ it.value.date }, { it.index }))?.value

    fun isDeepDay(date: LocalDate) = date.dayOfWeek == DayOfWeek.WEDNESDAY || date.dayOfWeek == DayOfWeek.SATURDAY

    fun week(state: JournalState, date: LocalDate): Int =
        (ChronoUnit.DAYS.between(state.startDate, date).coerceAtLeast(0) / 7).toInt() + 1

    fun plan(bank: List<Prompt>, state: JournalState, date: LocalDate, hour: Int): DailyPlan {
        val random = Random(date.toEpochDay() * 31 + state.startDate.toEpochDay())
        val chosen = ArrayList<PromptInstance>()
        var note: String? = null
        if (bank.any { it.perChild } && state.children.isEmpty()) {
            note = "Add your children's names in settings to get the prompts written for each of them."
        }
        val lastAnswer = lastOf(state)
        val yesterdayHeavy = lastAnswer?.heavy == true
        if (yesterdayHeavy) note = "That was a big one. Here's something easier today."

        if (state.morningNudge) {
            pick(bank, state, date, hour, random, PromptInstance.Slot.MORNING, chosen)?.let(chosen::add)
        }
        pick(bank, state, date, hour, random, PromptInstance.Slot.EVENING, chosen)?.let(chosen::add)
        return DailyPlan(date, chosen, note)
    }

    private class Candidate(val instance: PromptInstance, val weight: Double)

    private fun pick(
        bank: List<Prompt>, state: JournalState, date: LocalDate, hour: Int, random: Random,
        slot: PromptInstance.Slot, already: List<PromptInstance>
    ): PromptInstance? {
        val week = week(state, date)
        val answeredKeys = state.answers.groupBy { it.key }
        val takenToday = already.map { it.key }.toSet() + already.map { it.prompt.category }.toSet()
        val lastAnswer = lastOf(state)
        val yesterdayCategory = state.answers.filter { it.date == date.minusDays(1) }.map { it.category }.toSet()
        val heavy7 = state.answers.count { it.heavy && ChronoUnit.DAYS.between(it.date, date) in 0..6 }
        val lightenAfterSkips = state.skipDates.takeLast(2).let { it.size == 2 && ChronoUnit.DAYS.between(it[0], date) <= 7 }
        val heavyAllowed = slot == PromptInstance.Slot.EVENING && isDeepDay(date) && hour < HEAVY_CUTOFF_HOUR &&
            lastAnswer?.heavy != true && heavy7 < MAX_HEAVY_PER_7_DAYS && !lightenAfterSkips &&
            (state.heavyPausedUntil == null || date >= state.heavyPausedUntil)
        val mustBeLight = slot == PromptInstance.Slot.MORNING || lastAnswer?.heavy == true || lightenAfterSkips
        val phaseCats = phases.firstOrNull { week in it.first }?.second
        val categoryLastSeen = state.answers.groupBy { it.category }.mapValues { e -> e.value.maxOf { it.date } }
        val childCounts = state.children.associateWith { c -> state.answers.count { it.child == c } }

        fun instances(p: Prompt): List<Pair<PromptInstance, Boolean>> {
            val children: List<String?> = if (p.perChild || p.placeholders.contains("[child name]")) state.children else listOf(null)
            return children.mapNotNull { child ->
                val key = PromptInstance.keyOf(p.id, child)
                if (key in state.retired || key in takenToday) return@mapNotNull null
                state.snoozedUntil[key]?.let { if (date < it) return@mapNotNull null }
                val past = answeredKeys[key]
                val isRevisit = past != null
                if (isRevisit) {
                    if (p.revisit == Revisit.NONE) return@mapNotNull null
                    if (ChronoUnit.DAYS.between(past!!.maxOf { it.date }, date) < REVISIT_DAYS) return@mapNotNull null
                }
                PromptInstance(p, child, fill(p, child, state.partner), isRevisit, slot) to isRevisit
            }
        }

        fun candidates(relaxDepth: Boolean, relaxLight: Boolean = false): List<Candidate> = bank.flatMap { p ->
            if (p.heavy && !heavyAllowed) return@flatMap emptyList()
            // Light means depth 1; once those run out, a gentle depth-2 prompt (never a heavy one).
            if (mustBeLight && p.depth > (if (relaxLight) 2 else 1)) return@flatMap emptyList()
            if (slot == PromptInstance.Slot.EVENING && !mustBeLight && !p.heavy && p.depth == 1 && !relaxDepth) {
                // Evenings carry depth 2 (or 3 on deep days); depth 1 is for mornings and light days.
                return@flatMap emptyList()
            }
            // Never the same category two days running or twice in a day, even when the bank runs low:
            // an empty day is better than breaking the pacing rule.
            if (p.category in yesterdayCategory || p.category in takenToday) return@flatMap emptyList()
            instances(p).map { (inst, revisit) ->
                var w = 1.0
                if (week == 1) {
                    w = if (p.id in setupPrompts) 100.0 else 0.1
                } else if (phaseCats != null && p.category !in phaseCats) {
                    w *= 0.1
                }
                if (revisit) w += 1.0
                val last = categoryLastSeen[p.category]
                if (last == null || ChronoUnit.DAYS.between(last, date) >= 14) w += 0.5
                if (inst.child != null && childCounts[inst.child] == childCounts.values.minOrNull()) w += 0.4
                if (mustBeLight && p.category in lightCategories) w += 0.5
                if (heavyAllowed && p.heavy) w += 0.5
                Candidate(inst, w)
            }
        }

        val pool = candidates(relaxDepth = false).ifEmpty { candidates(relaxDepth = true) }
            .ifEmpty { if (slot == PromptInstance.Slot.EVENING) candidates(relaxDepth = false, relaxLight = true) else emptyList() }
            .ifEmpty { if (slot == PromptInstance.Slot.EVENING) candidates(relaxDepth = true, relaxLight = true) else emptyList() }
        if (pool.isEmpty()) return null
        val total = pool.sumOf { it.weight }
        var r = random.nextDouble() * total
        for (c in pool.sortedBy { it.instance.key }) {
            r -= c.weight
            if (r <= 0) return c.instance
        }
        return pool.last().instance
    }

    /** Fills "[child name]" and "[partner name]"; per-child prompts without a placeholder say who they are for. */
    fun fill(p: Prompt, child: String?, partner: String?): String {
        var t = p.text
        if (child != null) t = t.replace("[child name]", child)
        t = t.replace("[partner name]", partner?.takeIf { it.isNotBlank() } ?: "your partner")
        if (child != null && !p.text.contains("[child name]")) t = "For $child: $t"
        return t
    }
}
