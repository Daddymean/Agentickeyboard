package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.WritingQualityMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WritingQualityMeterTest {

    @Test
    fun lengthLabelBuckets() {
        assertEquals("Empty", WritingQualityMeter.lengthLabel(""))
        assertEquals("Empty", WritingQualityMeter.lengthLabel("   "))
        assertEquals("Very short", WritingQualityMeter.lengthLabel("on my way"))
        assertEquals("Tight", WritingQualityMeter.lengthLabel("I will be there in about ten minutes"))
        val fifty = (1..50).joinToString(" ") { "word" }
        assertEquals("Long for chat", WritingQualityMeter.lengthLabel(fifty))
        val hundred = (1..100).joinToString(" ") { "word" }
        assertEquals("Very long", WritingQualityMeter.lengthLabel(hundred))
    }

    @Test
    fun clarityFlagsRunOnSentences() {
        assertEquals("Clear", WritingQualityMeter.clarity("Short and sweet. See you soon."))
        val runOn = (1..35).joinToString(" ") { "word" }
        assertEquals("Dense", WritingQualityMeter.clarity(runOn))
    }

    @Test
    fun warmthDetectsWarmAndColdWording() {
        assertEquals("Warm", WritingQualityMeter.warmth("Thanks so much, really appreciate it!"))
        assertEquals("Cold", WritingQualityMeter.warmth("This is useless and a terrible idea"))
        assertEquals("Neutral", WritingQualityMeter.warmth("The meeting is at three"))
    }

    @Test
    fun firmnessSeparatesHedgesFromDemands() {
        assertEquals("Soft", WritingQualityMeter.firmness("Maybe we could possibly move it, I think?"))
        assertEquals("Firm", WritingQualityMeter.firmness("I need this done immediately, the deadline is final."))
        assertEquals("Balanced", WritingQualityMeter.firmness("The report is attached for review"))
    }

    @Test
    fun riskEscalatesOnHostilityAndShouting() {
        assertEquals(WritingQualityMeter.RISK_LOW, WritingQualityMeter.risk("See you at lunch tomorrow"))
        assertEquals(
            WritingQualityMeter.RISK_HIGH,
            WritingQualityMeter.risk("I am sick of this, you are useless")
        )
        assertEquals(WritingQualityMeter.RISK_HIGH, WritingQualityMeter.risk("THIS IS COMPLETELY UNACCEPTABLE"))
        assertNotEquals(WritingQualityMeter.RISK_HIGH, WritingQualityMeter.risk("So excited!! See you there"))
    }

    @Test
    fun riskIsLowForNormalText() {
        assertEquals(WritingQualityMeter.RISK_LOW, WritingQualityMeter.risk("Hello world. How are you today?"))
    }

    @Test
    fun riskIsMediumForSingleHostileHit() {
        assertEquals(WritingQualityMeter.RISK_MEDIUM, WritingQualityMeter.risk("This is stupid"))
    }

    @Test
    fun riskIsMediumForMultiplePunctuation() {
        assertEquals(WritingQualityMeter.RISK_MEDIUM, WritingQualityMeter.risk("What is this?? Tell me now!!"))
    }

    @Test
    fun riskIsHighForShoutingWithoutWarmWords() {
        // Needs at least 12 letters and >= 70% uppercase.
        // "I AM VERY ANGRY ABOUT THIS" has 22 letters, all uppercase.
        assertEquals(WritingQualityMeter.RISK_HIGH, WritingQualityMeter.risk("I AM VERY ANGRY ABOUT THIS"))
    }

    @Test
    fun riskIsLowForShoutingWithWarmWords() {
        assertEquals(WritingQualityMeter.RISK_LOW, WritingQualityMeter.risk("THANK YOU SO MUCH FOR YOUR HELP"))
    }

    @Test
    fun riskIsLowForShortUppercaseText() {
        // "HI THERE" has 7 letters, less than the 12 required for shouting.
        assertEquals(WritingQualityMeter.RISK_LOW, WritingQualityMeter.risk("HI THERE"))
    }

    @Test
    fun riskIsHighForHostilePlusPunctuation() {
        assertEquals(WritingQualityMeter.RISK_HIGH, WritingQualityMeter.risk("This is stupid!!"))
    }

    @Test
    fun assessProducesNoteAndAllDimensions() {
        val hostile = WritingQualityMeter.assess("I am sick of this, you are useless")
        assertEquals(WritingQualityMeter.RISK_HIGH, hostile.risk)
        assertTrue(hostile.note.contains("land badly"))

        val friendly = WritingQualityMeter.assess("Thanks again, see you tomorrow!")
        assertEquals("Warm", friendly.warmth)
        assertEquals("Very short", WritingQualityMeter.assess("on my way").lengthLabel)
        assertTrue(friendly.note.isNotBlank())
    }
}
