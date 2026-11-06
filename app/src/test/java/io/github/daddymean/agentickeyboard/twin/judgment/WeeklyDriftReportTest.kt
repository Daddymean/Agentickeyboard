package io.github.daddymean.agentickeyboard.twin.judgment

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeeklyDriftReportTest {
    private val keith = listOf(
        "yeah I'll grab it tomorrow", "don't worry about it, we're good",
        "can't today, kids have practice", "sounds good bud", "I'm out, call me later",
    )

    @Test fun weeklySchedule() {
        assertTrue(WeeklyDriftReport.due(null, 0))
        assertFalse(WeeklyDriftReport.due(0, WeeklyDriftReport.INTERVAL_MILLIS - 1))
        assertTrue(WeeklyDriftReport.due(0, WeeklyDriftReport.INTERVAL_MILLIS))
    }

    @Test fun flagsAvoidedPhraseFromApprovedValuesCard() {
        val card = ValuesCard(
            listOf(
                ValuesCard.Section(ValuesCard.TITLES[0], listOf("Family first")),
                ValuesCard.Section(ValuesCard.TITLES[1], emptyList()),
                ValuesCard.Section(ValuesCard.TITLES[2], emptyList()),
                ValuesCard.Section(ValuesCard.TITLES[3], listOf("Don't say \"delve into\"")),
            ),
            approvedAtMillis = 1L,
            updatedAtMillis = 1L,
        )
        val twin = List(5) { "Let me delve into that for you carefully." }
        val r = WeeklyDriftReport.run(twin, keith, card, DecisionJournal(), 10_000L)
        assertTrue(r.drifting)
        assertTrue(r.findings.any { it.kind == WeeklyDriftReport.Finding.Kind.VALUES && it.detail.contains("delve") })
        assertTrue(r.summary().contains("Weekly twin drift report"))
    }

    @Test fun flagsDecisionConflict() {
        val j = DecisionJournal().add(
            Decision("1", "Keep the truck", "won't sell the truck, kids need it", "keep it", 0.9, 1, 2),
        )
        val twin = List(5) { "You should sell the truck and take the cash." }
        val r = WeeklyDriftReport.run(twin, keith, null, j, 10_000L)
        assertTrue(r.findings.any { it.kind == WeeklyDriftReport.Finding.Kind.DECISION })
    }

    @Test fun cleanKeithLikeRepliesQuiet() {
        val card = ValuesCard(
            listOf(ValuesCard.Section(ValuesCard.TITLES[0], listOf("kids practice"))),
            approvedAtMillis = 1L,
            updatedAtMillis = 1L,
        )
        val r = WeeklyDriftReport.run(keith.map { "$it." }, keith, card, DecisionJournal(), 1L)
        assertFalse(r.findings.any { it.kind == WeeklyDriftReport.Finding.Kind.DECISION })
    }

    @Test fun extractAvoidedAndOpposingCueHelpers() {
        assertEquals("delve into", WeeklyDriftReport.extractAvoided("Don't say \"delve into\""))
        assertNull(WeeklyDriftReport.extractAvoided("Be direct"))
        assertEquals("should sell", WeeklyDriftReport.opposingCue("won't sell the house"))
        assertNull(WeeklyDriftReport.opposingCue("buy groceries"))
    }
}
