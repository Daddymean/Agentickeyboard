package io.github.daddymean.agentickeyboard.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TwinStyleStatsTest {

    @Test
    fun countsWordsPhrasesEmojiAndPunctuation() {
        val s = TwinStyleStats.of(
            listOf(
                "love you buddy 😂 see you soon!",
                "love you buddy, call me later...",
                "Proud of you. Love you buddy!!"
            )
        )
        assertEquals(3, s.messages)
        assertEquals("you" to 5, s.topWords.first())
        assertTrue(s.topBigrams.contains("love you" to 3))
        assertTrue(s.topTrigrams.contains("love you buddy" to 3))
        assertEquals(listOf("😂" to 1), s.topEmoji)
        assertEquals(100.0 / 3, s.punctuationPer100Messages.getValue("..."), 0.01)
        assertEquals(100.0 / 3, s.punctuationPer100Messages.getValue("!!"), 0.01)
        assertEquals(2.0 / 3, s.lowercaseStartShare, 0.001)
    }

    @Test
    fun formalityTellsCasualFromFormal() {
        val casual = TwinStyleStats.of(listOf("lol yeah gonna be late tbh", "ok u coming? haha", "idk dude"))
        val formal = TwinStyleStats.of(
            listOf("Dear team, thank you for the update.", "Kindly review the attached; regards, Keith.")
        )
        assertTrue("${casual.formality} < ${formal.formality}", casual.formality < formal.formality)
        assertTrue(casual.casualPer100Words > formal.casualPer100Words)
    }

    @Test
    fun redactionMarkersAreNotWords() {
        val s = TwinStyleStats.of(listOf("mail me at [REDACTED_EMAIL] ok"))
        assertTrue(s.topWords.none { it.first.contains("redacted") })
    }

    @Test
    fun emptyInputIsAllZero() {
        val s = TwinStyleStats.of(emptyList())
        assertEquals(0, s.messages)
        assertEquals(0.0, s.avgWordsPerSentence, 0.0)
    }
}
