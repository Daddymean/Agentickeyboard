package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.ui.WordCommitResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** KEYBOARD-010: apostrophe-less contractions and a lone "i". */
class ContractionsTest {

    private val spelling = LocalSpelling(File("src/main/res/raw/wordlist.txt").readLines().take(10_000))
    private val rules = listOf(LearnedCorrection(typo = "teh", correction = "the"))

    private fun space(word: String, paused: Boolean = false, sensitive: Boolean = false) =
        WordCommitResolver.resolve(word, emptyList(), rules, paused, { TextExpansion.ExpandedText(it) }, sensitive)?.replacement

    /** The 20 contraction cases in the KEYBOARD-FEATURES-001 harness. */
    private val harness = listOf(
        "dont" to "don't", "im" to "I'm", "cant" to "can't", "wont" to "won't", "didnt" to "didn't",
        "ive" to "I've", "youre" to "you're", "thats" to "that's", "isnt" to "isn't", "doesnt" to "doesn't",
        "i" to "I", "id" to "I'd", "ill" to "I'll", "whats" to "what's", "theyre" to "they're",
        "wasnt" to "wasn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't", "wouldnt" to "wouldn't", "hes" to "he's"
    )
    private val ambiguous = listOf("ill", "id", "its", "were", "well", "hell", "shed", "shell", "wed", "lets", "hed")

    @Test
    fun `space applies unambiguous contractions and capital I`() {
        harness.filter { it.first !in ambiguous }.forEach { (typed, expected) ->
            assertEquals(typed, expected, space(typed))
        }
        assertEquals(18, harness.count { space(it.first) == it.second })
    }

    @Test
    fun `space never applies an ambiguous form`() {
        ambiguous.forEach { assertNull(it, space(it)) }
        ambiguous.forEach { assertNull(it, space(it.replaceFirstChar { c -> c.uppercase() })) }
    }

    @Test
    fun `case, punctuation, apostrophe forms, pause and sensitive fields`() {
        assertEquals("Don't", space("Dont"))
        assertEquals("DON'T", space("DONT"))
        assertEquals("don't,", space("dont,"))
        assertEquals("I.", space("i."))
        assertEquals("I'm", space("i'm"))
        assertEquals("I'll", space("i’ll"))
        assertNull(space("I"))
        assertNull(space("I'm"))
        assertNull(space("don't"))
        assertNull(space("dont", paused = true))
        assertNull(space("dont", sensitive = true))
        assertNull(space("i", sensitive = true))
        // Learned rules still come first.
        assertEquals("the", space("teh"))
    }

    @Test
    fun `strip offers the contraction first for all 20 harness cases`() {
        harness.forEach { (typed, expected) ->
            val strip = spelling.predictiveSuggestions(typed, emptyList(), null)
            assertEquals("$typed → $strip", expected, strip.first())
        }
    }

    @Test
    fun `strip keeps common real words first and offers their contraction second`() {
        listOf("its" to "it's", "were" to "we're", "well" to "we'll", "lets" to "let's").forEach { (typed, contraction) ->
            val strip = spelling.predictiveSuggestions(typed, emptyList(), null)
            assertNotEquals(typed, contraction, strip.first())
            assertTrue("$typed → $strip", contraction in strip)
        }
        assertEquals("Don't", spelling.predictiveSuggestions("Dont", emptyList(), null).first())
    }
}
