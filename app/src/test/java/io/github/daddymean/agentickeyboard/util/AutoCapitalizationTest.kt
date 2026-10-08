package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** KEYBOARD-007: no automatic capitalization in password, sensitive or incognito editors. */
class AutoCapitalizationTest {

    private val sentenceStarts = listOf("", "Hi.\n", "\n", "Done. ", "Really? ", "Wow! ", "   ")
    private val midSentence = listOf("hello", "hello ", "a, ", "Dr", "x.y")

    @Test
    fun `sentence start detection is unchanged`() {
        sentenceStarts.forEach { assertTrue("'$it' starts a sentence", AutoCapitalization.isSentenceStart(it)) }
        midSentence.forEach { assertFalse("'$it' is mid-sentence", AutoCapitalization.isSentenceStart(it)) }
    }

    @Test
    fun `ordinary field auto-capitalizes at a sentence start when enabled`() {
        sentenceStarts.forEach { assertTrue(AutoCapitalization.applies(true, sensitiveField = false, textBeforeCursor = it)) }
        midSentence.forEach { assertFalse(AutoCapitalization.applies(true, sensitiveField = false, textBeforeCursor = it)) }
    }

    @Test
    fun `sensitive field never auto-capitalizes`() {
        (sentenceStarts + midSentence).forEach {
            assertFalse("'$it'", AutoCapitalization.applies(true, sensitiveField = true, textBeforeCursor = it))
        }
    }

    @Test
    fun `disabled setting never auto-capitalizes`() {
        sentenceStarts.forEach { assertFalse(AutoCapitalization.applies(false, sensitiveField = false, textBeforeCursor = it)) }
    }
}
