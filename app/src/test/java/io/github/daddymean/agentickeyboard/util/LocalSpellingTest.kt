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
        assertEquals(listOf("hello", "help", "held"), spelling.suggestions("hel"))
    }

    @Test fun `personal completions precede spelling and dictionary guesses`() {
        assertEquals(listOf("helicopter", "helmet", "helpful"),
            spelling.predictiveSuggestions("hel", listOf("helicopter", "helmet", "helpful"), null))
        assertEquals(listOf("hello", "help", "held"), spelling.suggestions("hel"))
        assertEquals(listOf("the"), spelling.predictiveSuggestions("teh", emptyList(), null))
    }

    @Test fun `accepting corrections preserves sentence and caps case`() {
        assertEquals(listOf("The"), spelling.suggestions("Teh"))
        assertEquals(listOf("THE"), spelling.suggestions("TEH"))
        assertEquals(listOf("Hello", "Help", "Held"), spelling.predictiveSuggestions("Hel", emptyList(), null))
    }

    @Test fun `known words are never offered an edit guess`() {
        assertTrue(LocalSpelling(listOf("ill", "will")).suggestions("ill").isEmpty())
    }

    @Test fun `punctuation and very long inputs do not trigger corrections`() {
        assertTrue(spelling.suggestions("name@example.com").isEmpty())
        assertTrue(spelling.suggestions("x".repeat(100)).isEmpty())
    }

    @Test fun `shared deletion candidates require a single edit`() {
        assertTrue(LocalSpelling(listOf("bcda")).suggestions("abcd").isEmpty())
    }
}
