package io.github.daddymean.agentickeyboard.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiManagerTest {

    @Test
    fun parseReplyLines_nullOrBlank_returnsNull() {
        assertNull(GeminiManager.parseReplyLines(null))
        assertNull(GeminiManager.parseReplyLines(""))
        assertNull(GeminiManager.parseReplyLines("   \n   "))
    }

    @Test
    fun parseReplyLines_removesBulletsAndTrims() {
        val input = """
            -   Hello
            * World
            •   How are you?
        """.trimIndent()
        val expected = listOf("Hello", "World", "How are you?")
        assertEquals(expected, GeminiManager.parseReplyLines(input))
    }

    @Test
    fun parseReplyLines_removesNumbers() {
        val input = """
            1. Hello
            2) World
            10: How are you?
        """.trimIndent()
        val expected = listOf("Hello", "World", "How are you?")
        assertEquals(expected, GeminiManager.parseReplyLines(input))
    }

    @Test
    fun parseReplyLines_removesQuotes() {
        val input = """
            "Hello"
            "World
            "How are you?"
        """.trimIndent()
        // The implementation removes quotes from both ends (trim('"')).
        // Note: the second line only has a leading quote, which will be trimmed.
        val expected = listOf("Hello", "World", "How are you?")
        assertEquals(expected, GeminiManager.parseReplyLines(input))
    }

    @Test
    fun parseReplyLines_filtersBlankLines() {
        val input = """
            Hello

            World

        """.trimIndent()
        val expected = listOf("Hello", "World")
        assertEquals(expected, GeminiManager.parseReplyLines(input))
    }

    @Test
    fun parseReplyLines_removesDuplicates() {
        val input = """
            Hello
            World
            Hello
        """.trimIndent()
        val expected = listOf("Hello", "World")
        assertEquals(expected, GeminiManager.parseReplyLines(input))
    }

    @Test
    fun parseReplyLines_takesMaxThree() {
        val input = """
            One
            Two
            Three
            Four
            Five
        """.trimIndent()
        val expected = listOf("One", "Two", "Three")
        assertEquals(expected, GeminiManager.parseReplyLines(input))
    }

    @Test
    fun parseReplyLines_complexScenario() {
        val input = """

            - "1. First option"
            * 2) "Second option"
            • 3: Third option
            - Fourth option

        """.trimIndent()

        // Let's trace the execution of parseReplyLines for this complex input
        // 1. "- \"1. First option\"" -> trim().removePrefix("-").... -> "\"1. First option\""
        //    -> replace(REPLY_NUMBER_PREFIX, "") -> "\"1. First option\""
        //       (REPLY_NUMBER_PREFIX is "^\\d+[.):]\\s*". Since it starts with quote, it doesn't match prefix)
        //       Wait, the function removes bullet THEN replaces number THEN trims quotes.
        //       Line: "- \"1. First option\""
        //       - trim().removePrefix("-")... -> "\"1. First option\""
        //       - replace(REPLY_NUMBER_PREFIX, "") -> matches "^\\d+...". No, starts with quote.
        //       - trim('"') -> "1. First option"
        //
        //       Line: "* 2) \"Second option\""
        //       - trim... -> "2) \"Second option\""
        //       - replace number -> "\"Second option\""
        //       - trim('"') -> "Second option"
        //
        //       Line: "• 3: Third option"
        //       - trim... -> "3: Third option"
        //       - replace number -> "Third option"
        //       - trim('"') -> "Third option"
        //
        //       Line: "- Fourth option"
        //       - trim... -> "Fourth option"

        val expected = listOf("1. First option", "Second option", "Third option")
        assertEquals(expected, GeminiManager.parseReplyLines(input))
    }
}
