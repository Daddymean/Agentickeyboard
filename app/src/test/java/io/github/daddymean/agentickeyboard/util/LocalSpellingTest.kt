package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.*
import org.junit.Test

class LocalSpellingTest {
    private val spelling = LocalSpelling(listOf("the", "should", "suggestions", "keyboard", "hello", "help", "held"))

    @Test fun `finds substitutions omitted letters and transpositions`() {
        assertTrue("should" in spelling.suggestions("shoulf"))
        assertTrue("suggestions" in spelling.suggestions("suggestins"))
        assertTrue("the" in spelling.suggestions("teh"))
        assertTrue("keyboard" in spelling.suggestions("keyboaard"))
    }

    @Test fun `known words are not corrected and prefixes still complete`() {
        assertFalse("the" in spelling.suggestions("the"))
        assertEquals(listOf("help", "held", "hello"), spelling.suggestions("hel"))
    }

    @Test fun `punctuation and very long inputs do not trigger corrections`() {
        assertTrue(spelling.suggestions("name@example.com").isEmpty())
        assertTrue(spelling.suggestions("x".repeat(100)).isEmpty())
    }

    @Test fun `shared deletion candidates require a single edit`() {
        assertTrue(LocalSpelling(listOf("bcda")).suggestions("abcd").isEmpty())
    }
}
