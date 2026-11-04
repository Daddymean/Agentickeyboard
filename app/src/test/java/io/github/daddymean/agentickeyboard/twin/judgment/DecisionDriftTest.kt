package io.github.daddymean.agentickeyboard.twin.judgment

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DecisionDriftTest {
    private fun d(id: String, conf: Double, review: Long) = Decision(id, "Buy truck $id", "Need towing", "Pays off in 2 years", conf, 0, review)

    @Test fun dueOnlyWhenReviewDateCameAndNotReviewed() {
        val j = DecisionJournal().add(d("a", 0.8, 100)).add(d("b", 0.6, 200))
        assertEquals(listOf("a"), j.due(150).map { it.id })
        assertTrue(j.recordOutcome("a", "worked", true).due(150).isEmpty())
    }

    @Test fun brierScoresForecasts() {
        assertNull(DecisionJournal().brier())
        val j = DecisionJournal().add(d("a", 0.8, 1)).add(d("b", 0.6, 1))
            .recordOutcome("a", "yes", true).recordOutcome("b", "no", false)
        assertEquals(((0.2 * 0.2) + (0.6 * 0.6)) / 2, j.brier()!!, 1e-9)
    }

    @Test fun rejectsBadInput() {
        assertTrue(runCatching { d("x", 1.5, 1) }.isFailure)
        assertTrue(runCatching { DecisionJournal().add(d("a", 0.5, 1).copy(why = " ")) }.isFailure)
        assertTrue(runCatching { DecisionJournal().add(d("a", 0.5, 1)).add(d("a", 0.5, 1)) }.isFailure)
    }

    @Test fun journalRoundTrips() {
        val j = DecisionJournal().add(d("a", 0.7, 9)).add(d("b", 0.3, 9)).recordOutcome("a", "fine", true)
        assertEquals(j.decisions, DecisionJournal.fromJson(JSONObject(j.toJson().toString())).decisions)
        assertEquals(listOf("Need towing"), j.whys())
    }

    private val keith = listOf("yeah I'll grab it tomorrow", "don't worry about it, we're good", "can't today, kids have practice",
        "sounds good bud", "I'm out, call me later")

    @Test fun genericAssistantVoiceIsFlagged() {
        val twin = List(5) { "Certainly! I would be happy to help with that. It is important to consider every option. Let me know if you need anything else." }
        val r = DriftCheck.compare(twin, keith)!!
        assertTrue(r.drifting)
        assertTrue(r.reasons.any { it.contains("generic") })
        assertTrue(r.reasons.any { it.contains("longer") })
    }

    @Test fun keithLikeRepliesAreNotFlaggedAndSmallSamplesSkipped() {
        assertFalse(DriftCheck.compare(keith.map { "$it." }, keith)!!.drifting)
        assertNull(DriftCheck.compare(keith.take(2), keith))
    }

    @Test fun monthlySchedule() {
        assertTrue(DriftCheck.due(null, 0))
        assertFalse(DriftCheck.due(0, DriftCheck.INTERVAL_MILLIS - 1))
        assertTrue(DriftCheck.due(0, DriftCheck.INTERVAL_MILLIS))
    }
}
