package io.github.daddymean.agentickeyboard.network

import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure output-cleanup helpers in GeminiManager (no network involved). */
class GeminiManagerTest {

    // --- stripDraftEcho: drops a continuation's leading repeat of the draft ---

    @Test
    fun stripDraftEchoRemovesLeadingEchoIgnoringCase() {
        assertEquals("world", GeminiManager.stripDraftEcho("hello", "hello world"))
        assertEquals("world", GeminiManager.stripDraftEcho("HeLlO", "hello world"))
        // The remainder keeps the model's casing.
        assertEquals("WORLD", GeminiManager.stripDraftEcho("hello", "HELLO WORLD"))
    }

    @Test
    fun stripDraftEchoTrimsBothInputsAndTheGap() {
        assertEquals("world", GeminiManager.stripDraftEcho("  hello  ", "  hello    world  "))
    }

    @Test
    fun stripDraftEchoLeavesNonEchoesTrimmed() {
        assertEquals("hello world", GeminiManager.stripDraftEcho("hi", "  hello world "))
        assertEquals("hello world", GeminiManager.stripDraftEcho("", "hello world"))
        assertEquals("hello world", GeminiManager.stripDraftEcho("   ", "hello world"))
        assertEquals("Hello", GeminiManager.stripDraftEcho("Hello World", "Hello"))
    }

    @Test
    fun stripDraftEchoDoesNotStripAPrefixThatEndsMidWord() {
        // The draft must end on a word boundary in the continuation to count as an echo.
        assertEquals("hello", GeminiManager.stripDraftEcho("he", "hello"))
        assertEquals("and then we left", GeminiManager.stripDraftEcho("a", "and then we left"))
        assertEquals("Thanksgiving plans?", GeminiManager.stripDraftEcho("thanks", "Thanksgiving plans?"))
        assertEquals("I said hello there", GeminiManager.stripDraftEcho("I said he", "I said hello there"))
        assertEquals("route 66b", GeminiManager.stripDraftEcho("route 66", "route 66b"))
        // A contraction continues the word, so "I" is not an echo inside "I'll".
        assertEquals("I'll be there", GeminiManager.stripDraftEcho("I", "I'll be there"))
        assertEquals("don\u2019t worry", GeminiManager.stripDraftEcho("don", "don\u2019t worry"))
    }

    @Test
    fun stripDraftEchoStripsAtWhitespaceOrPunctuationBoundaries() {
        assertEquals(", see you soon", GeminiManager.stripDraftEcho("hello", "hello, see you soon"))
        assertEquals("!", GeminiManager.stripDraftEcho("hello", "hello!"))
        // A draft that itself ends in punctuation or a space is always a clean boundary.
        assertEquals("world", GeminiManager.stripDraftEcho("hello,", "hello,world"))
        assertEquals("there", GeminiManager.stripDraftEcho("hi ", "hi there"))
    }

    @Test
    fun stripDraftEchoNeverReturnsEmptyForAPureEcho() {
        assertEquals("hello", GeminiManager.stripDraftEcho("hello", "hello"))
        assertEquals("hello", GeminiManager.stripDraftEcho("hello", " hello   "))
    }

    @Test
    fun stripDraftEchoOfBlankContinuationIsEmpty() {
        assertEquals("", GeminiManager.stripDraftEcho("hello", ""))
        assertEquals("", GeminiManager.stripDraftEcho("hello", "   "))
    }

    // --- parseReplyLines: cases beyond OnDeviceAiRoutingTest's coverage ---

    @Test
    fun parseReplyLinesStripsEveryNumberingStyle() {
        assertEquals(
            listOf("Hello", "World", "How are you?"),
            GeminiManager.parseReplyLines("1. Hello\n2) World\n10: How are you?")
        )
    }

    @Test
    fun parseReplyLinesStripsBulletsAndUnbalancedQuotes() {
        assertEquals(
            listOf("Hello", "World", "How are you?"),
            GeminiManager.parseReplyLines("-   Hello\n* \"World\n•   \"How are you?\"")
        )
    }

    @Test
    fun parseReplyLinesSkipsBlankLinesBeforeCapping() {
        assertEquals(
            listOf("One", "Two", "Three"),
            GeminiManager.parseReplyLines("\nOne\n\n  \nTwo\n- \nThree\nFour")
        )
    }
}
