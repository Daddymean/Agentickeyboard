package io.github.daddymean.agentickeyboard.twin.judgment

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DisagreementValuesTest {
    private fun c(twin: String, keith: String, at: Long) = Correction(twin, keith, null, at)

    @Test fun ignoresEmptyAndNoOpCorrections() {
        val log = DisagreementLog().add(c("", "x", 1)).add(c("Same thing.", "same thing", 2)).add(c("a", " ", 3))
        assertTrue(log.corrections.isEmpty())
    }

    @Test fun repeatedRemovedPhrasesBecomeAvoidPhrases() {
        val log = DisagreementLog()
            .add(c("I'd be happy to help you with that, buddy", "Sure, buddy", 1))
            .add(c("I'd be happy to look at it", "I'll look at it", 2))
            .add(c("Totally awesome news", "Good news", 3))
        val avoid = log.avoidPhrases()
        assertTrue("i'd be" in avoid && "be happy" in avoid)
        assertTrue("totally awesome" !in avoid) // only once
        assertTrue(log.penalty("I'd be happy to") > log.penalty("I'll do it"))
    }

    @Test fun newestExamplesFirstAndCapped() {
        var log = DisagreementLog()
        for (i in 1..(DisagreementLog.MAX + 5)) log = log.add(c("twin $i", "keith $i", i.toLong()))
        assertEquals(DisagreementLog.MAX, log.corrections.size)
        assertEquals("keith ${DisagreementLog.MAX + 5}", log.notMeExamples(1).single().keithText)
    }

    @Test fun logRoundTrips() {
        val log = DisagreementLog().add(Correction("a b", "c d", "texting Kid 2", 5))
        assertEquals(log.corrections, DisagreementLog.fromJson(JSONObject(log.toJson().toString())).corrections)
    }

    @Test fun draftIsNeverAnAnchorUntilKeithApproves() {
        val log = DisagreementLog().add(c("I'd be happy to", "ok", 1)).add(c("I'd be happy to x", "fine", 2))
        val draft = ValuesCard.draft(listOf("Family first", "Family first", " "), listOf("Sleep on big buys"), log, 10)
        assertNull(draft.anchor())
        assertEquals(listOf("Family first"), draft.sections[0].lines)
        assertTrue(draft.sections[3].lines.any { it.contains("i'd be") })
        val ok = draft.approveWithEdits(draft.sections, 20)
        assertNotNull(ok.anchor())
        assertTrue(ok.anchor()!!.contains("- Family first"))
        assertNull("a machine redraft drops approval", ok.redraft(ok.sections, 30).anchor())
    }

    @Test fun cardRoundTripsAndCapsLines() {
        val card = ValuesCard.draft((1..20).map { "v$it" }, emptyList(), DisagreementLog(), 1).approveWithEdits(
            listOf(ValuesCard.Section("What matters most", (1..20).map { "v$it" })), 2)
        assertEquals(ValuesCard.MAX_LINES, card.sections[0].lines.size)
        assertEquals(card, ValuesCard.fromJson(JSONObject(card.toJson().toString())))
        val draft = ValuesCard.draft(listOf("x"), emptyList(), DisagreementLog(), 1)
        assertEquals(draft, ValuesCard.fromJson(JSONObject(draft.toJson().toString())))
    }
}
