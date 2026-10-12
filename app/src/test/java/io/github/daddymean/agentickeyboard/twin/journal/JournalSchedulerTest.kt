package io.github.daddymean.agentickeyboard.twin.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** KEYBOARD-025: the pacing rules of schedule.md, checked over simulated years. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalSchedulerTest {
    private val bank = QuestionBank.parse(JournalAssets.asset("question-bank.json"))
    private val byId = bank.associateBy { it.id }
    private val start = LocalDate.of(2026, 10, 12)

    private class Day(val date: LocalDate, val prompts: List<PromptInstance>)

    /** Answers every planned prompt for [days] days, at [hour]. */
    private fun simulate(days: Int, initial: JournalState, hour: (LocalDate) -> Int = { 19 }): Pair<JournalState, List<Day>> {
        var state = initial
        val log = ArrayList<Day>()
        for (d in 0 until days) {
            val date = start.plusDays(d.toLong())
            val plan = JournalScheduler.plan(bank, state, date, hour(date))
            log += Day(date, plan.prompts)
            state = state.copy(answers = state.answers + plan.prompts.map {
                AnsweredPrompt(it.prompt.id, it.child, date, it.prompt.category, it.prompt.heavy)
            })
        }
        return state to log
    }

    private val family = JournalState(startDate = start, children = listOf("Ava", "Ben"), partner = "Sam", morningNudge = true)

    @Test
    fun pacingRulesHoldOverAYear() {
        val (state, log) = simulate(420, family)
        val answers = state.answers
        assertTrue(log.all { it.prompts.size <= 2 })
        // Answering two a day uses up the bank in a few months; until then every day has a prompt.
        assertTrue(log.take(100).all { it.prompts.isNotEmpty() })
        assertTrue(log.all { d -> d.prompts.count { it.slot == PromptInstance.Slot.EVENING } <= 1 })
        // Mornings are light nudges.
        assertTrue(log.flatMap { it.prompts }.filter { it.slot == PromptInstance.Slot.MORNING }.all { it.prompt.depth == 1 && !it.prompt.heavy })
        // Heavy only on deep-day evenings, never twice in a row, at most 3 in 7 days.
        val heavy = answers.filter { it.heavy }
        assertTrue(heavy.isNotEmpty())
        assertTrue(heavy.all { it.date.dayOfWeek == DayOfWeek.WEDNESDAY || it.date.dayOfWeek == DayOfWeek.SATURDAY })
        val ordered = log.flatMap { it.prompts }
        for (i in 1 until ordered.size) assertFalse("two heavy in a row", ordered[i - 1].prompt.heavy && ordered[i].prompt.heavy)
        for (d in log) assertTrue(heavy.count { ChronoUnit.DAYS.between(it.date, d.date) in 0..6 } <= JournalScheduler.MAX_HEAVY_PER_7_DAYS)
        // No category on two days running, and no category twice in a day.
        for (i in 1 until log.size) {
            val y = log[i - 1].prompts.map { it.prompt.category }.toSet()
            val t = log[i].prompts.map { it.prompt.category }
            assertTrue("${log[i].date}: $t after $y", t.none { it in y })
            assertEquals(t.size, t.toSet().size)
        }
        // Nothing is repeated within a year; yearly items may come back after 365 days.
        for ((key, list) in answers.groupBy { it.key }) {
            val dates = list.map { it.date }.sorted()
            for (i in 1 until dates.size) {
                assertTrue("$key repeated early", ChronoUnit.DAYS.between(dates[i - 1], dates[i]) >= JournalScheduler.REVISIT_DAYS)
                assertTrue("$key is not a yearly item", byId.getValue(list[0].promptId).revisit != Revisit.NONE)
            }
        }
        assertTrue("revisits happen after a year: " + log.drop(360).map { it.prompts.size } + " last=" + log.lastOrNull { it.prompts.isNotEmpty() }?.date,
            answers.groupBy { it.key }.values.any { it.size > 1 })
        // Per-child prompts go to every child, roughly evenly, with names filled in.
        val perChild = ordered.filter { it.child != null }
        assertTrue(perChild.isNotEmpty())
        val counts = perChild.groupingBy { it.child!! }.eachCount()
        assertEquals(setOf("Ava", "Ben"), counts.keys)
        assertTrue(counts.toString(), kotlin.math.abs(counts.getValue("Ava") - counts.getValue("Ben")) <= 3)
        assertTrue(ordered.none { it.text.contains("[child name]") || it.text.contains("[partner name]") })
        // Week 1 favours the setup boundaries; they share one category, so they are spread
        // over alternate days rather than breaking the no-repeat rule.
        val setup = setOf("BD-001", "BD-002", "BD-003", "BD-012")
        val early = log.take(14).flatMap { it.prompts }.map { it.prompt.id }
        assertTrue(early.toString(), early.count { it in setup } >= 3)
    }

    @Test
    fun noHeavyPromptsLateAtNightOrWhilePaused() {
        val (late, _) = simulate(120, family) { 22 }
        assertTrue(late.answers.none { it.heavy })
        val (paused, _) = simulate(120, family.copy(heavyPausedUntil = start.plusDays(200)))
        assertTrue(paused.answers.none { it.heavy })
        val (resumed, _) = simulate(120, family.copy(heavyPausedUntil = start.plusDays(30)))
        assertTrue(resumed.answers.filter { it.heavy }.all { it.date >= start.plusDays(30) })
        assertTrue(resumed.answers.any { it.heavy })
    }

    @Test
    fun planIsDeterministic() {
        val (state, _) = simulate(60, family)
        val date = start.plusDays(60)
        assertEquals(JournalScheduler.plan(bank, state, date, 19), JournalScheduler.plan(bank, state, date, 19))
    }

    @Test
    fun skippedPromptsWaitTwoWeeksAndRetiredOnesNeverReturn() {
        val (base, _) = simulate(40, family)
        val day = start.plusDays(40)
        val first = JournalScheduler.plan(bank, base, day, 19).prompts.last()
        val snoozed = base.copy(snoozedUntil = mapOf(first.key to day.plusDays(JournalScheduler.SNOOZE_DAYS)))
        for (d in 0 until JournalScheduler.SNOOZE_DAYS) {
            assertTrue(JournalScheduler.plan(bank, snoozed, day.plusDays(d), 19).prompts.none { it.key == first.key })
        }
        val retiredKeys = bank.take(150).flatMap { p -> listOf(p.id, "${p.id}#Ava", "${p.id}#Ben") }.toSet()
        val (retired, _) = simulate(200, family.copy(retired = retiredKeys))
        assertTrue(retired.answers.none { it.key in retiredKeys })
    }

    @Test
    fun aHeavyAnswerOrTwoSkipsLightenTheNextPrompt() {
        val heavyPrompt = bank.first { it.heavy && !it.perChild }
        val wed = LocalDate.of(2026, 12, 2)
        val afterHeavy = family.copy(startDate = start, answers = listOf(AnsweredPrompt(heavyPrompt.id, null, wed.minusDays(1), heavyPrompt.category, true)))
        val plan = JournalScheduler.plan(bank, afterHeavy, wed, 19)
        assertTrue(plan.prompts.all { it.prompt.depth == 1 && !it.prompt.heavy })
        assertTrue(plan.note!!.contains("easier"))
        val skipped = family.copy(skipDates = listOf(wed.minusDays(2), wed.minusDays(1)))
        assertTrue(JournalScheduler.plan(bank, skipped, wed, 19).prompts.all { it.prompt.depth == 1 })
    }

    @Test
    fun withoutChildrenPerChildPromptsWaitAndANoteSaysWhy() {
        val (state, log) = simulate(200, JournalState(startDate = start))
        assertTrue(state.answers.none { it.child != null })
        assertTrue(log.flatMap { it.prompts }.none { byId.getValue(it.prompt.id).perChild })
        assertTrue(JournalScheduler.plan(bank, state, start.plusDays(200), 19).note!!.contains("children"))
    }

    @Test
    fun historyRebuildsStateFromRecords() {
        val p = bank.first { !it.perChild }
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        fun at(d: LocalDate) = d.atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val records = listOf(
            JournalRecord(1, at(start), JournalKinds.SETTINGS, JournalSettings(listOf("Ava"), null, false, null, start).toJson()),
            JournalRecord(2, at(start.plusDays(1)), JournalKinds.ANSWER, org.json.JSONObject().put("promptId", p.id).put("text", "x")),
            JournalRecord(3, at(start.plusDays(1)), JournalKinds.ANSWER, org.json.JSONObject().put("promptId", p.id).put("followUp", true)),
            JournalRecord(4, at(start.plusDays(2)), JournalKinds.EVENT, org.json.JSONObject().put("type", "skip").put("key", "X-1")),
            JournalRecord(5, at(start.plusDays(3)), JournalKinds.EVENT, org.json.JSONObject().put("type", "retire").put("key", "X-2")),
            JournalRecord(6, at(start.plusDays(3)), JournalKinds.EVENT, org.json.JSONObject().put("type", "retire").put("key", "X-3")),
            JournalRecord(7, at(start.plusDays(4)), JournalKinds.EVENT, org.json.JSONObject().put("type", "unretire").put("key", "X-3")),
            JournalRecord(8, at(start.plusDays(4)), JournalKinds.INSTRUMENT, org.json.JSONObject().put("instrumentId", "mfq2").put("responses", org.json.JSONObject().put("1", 2).put("2", 5))),
            JournalRecord(9, at(start.plusDays(5)), JournalKinds.INSTRUMENT, org.json.JSONObject().put("instrumentId", "mfq2").put("responses", org.json.JSONObject().put("1", 4)))
        )
        val s = JournalHistory.state(records, bank, start.plusDays(6), zone)
        assertEquals(listOf("Ava"), s.children)
        assertEquals(1, s.answers.size)
        assertEquals(start.plusDays(2 + JournalScheduler.SNOOZE_DAYS), s.snoozedUntil["X-1"])
        assertEquals(setOf("X-2"), s.retired)
        assertEquals(listOf(start.plusDays(2)), s.skipDates)
        assertEquals(mapOf(1 to 4, 2 to 5), JournalHistory.responses(records, "mfq2"))
    }
}
