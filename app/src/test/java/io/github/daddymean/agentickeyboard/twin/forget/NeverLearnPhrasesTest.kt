package io.github.daddymean.agentickeyboard.twin.forget

import io.github.daddymean.agentickeyboard.twin.NeverLearnPhrases
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeverLearnPhrasesTest {
    @Test
    fun matchesWholeWordsInARowFoldingCaseAndAccents() {
        val e = setOf(NeverLearnPhrases.entry("Café Nero")!!)
        assertTrue(NeverLearnPhrases.matches("meet at CAFE nero?", e))
        assertFalse(NeverLearnPhrases.matches("nero cafe", e))
        assertFalse(NeverLearnPhrases.matches("cafeteria nero", e))
        assertFalse(NeverLearnPhrases.matches("anything", emptySet()))
    }

    @Test
    fun storesOnlyAHash() {
        val e = NeverLearnPhrases.entry("my secret diagnosis")!!
        assertTrue(e.startsWith("3:"))
        assertFalse(e.contains("secret") || e.contains("diagnosis"))
        assertEquals(e, NeverLearnPhrases.entry("  My SECRET diagnosis! "))
    }

    @Test
    fun refusesEmptyOrOverlongPhrases() {
        assertNull(NeverLearnPhrases.entry("  ...  "))
        assertNull(NeverLearnPhrases.entry((1..9).joinToString(" ") { "w$it" }))
        assertTrue(NeverLearnPhrases.contains("one two three", "two three"))
        assertFalse(NeverLearnPhrases.contains("one two three", "three two"))
    }
}
