package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KEYBOARD-020: common words missing from the frequency list ("discern") come from
 * SCOWL levels 10-35, loaded the way AgenticKeyboardApplication loads them.
 */
class DictionaryGapsTest {
    private fun read(name: String) = File("src/main/res/raw/$name").readLines().map { it.trim() }.filter { it.isNotEmpty() }

    private val words = read("wordlist.txt")
    private val extra = read("spelling_extra.txt")
    private val knownOnly = read("spelling_known_only.txt")
    private val spelling = LocalSpelling(words.take(10_000), knownOnly, extra)

    private fun strip(word: String) = spelling.predictiveSuggestions(word, emptyList(), null, 3, true, null)

    @Test
    fun `discern and other common words are known and offered`() {
        listOf("discern", "discerning", "reasoning", "obey", "thwart", "deem").forEach {
            assertTrue(it, spelling.isKnownWord(it))
        }
        assertEquals("discern", strip("diacwrn").first())
        // A correctly typed extra word is not replaced by a correction.
        strip("discern").forEach { assertTrue(it, it.startsWith("discern")) }
    }

    @Test
    fun `frequent words still win close calls`() {
        assertEquals("spelling", strip("speelng").first())
        assertEquals("the", strip("thw").first())
    }

    @Test
    fun `vulgar and offensive words are recognised but never suggested`() {
        assertTrue(knownOnly.isNotEmpty())
        knownOnly.forEach { word ->
            assertTrue(word, spelling.isKnownWord(word))
            assertFalse(word, word in extra)
            listOf(word.dropLast(1), word.drop(1)).filter { it.length >= 2 }.forEach { typed ->
                assertFalse("$typed → $word", strip(typed).any { it.equals(word, ignoreCase = true) })
            }
        }
    }

    @Test
    fun `extra list keeps contractions and common slang out`() {
        val extraSet = extra.toSet()
        (Contractions.UNAMBIGUOUS.keys + LocalSpelling.COMMON_SLANG).forEach { assertFalse(it, it in extraSet) }
        assertTrue(extra.all { w -> w.all { it in 'a'..'z' } })
        assertTrue(extra.size > 30_000)
    }
}
