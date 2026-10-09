package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.ui.WordCommitResolver
import io.github.daddymean.agentickeyboard.ui.WordReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KEYBOARD-008: Fix Grammar must not save rules that rewrite real words. The
 * pairs here are what the old extractor learned, so the same examples fail on
 * the old code.
 */
class LearnedRuleFilterTest {

    private val spelling = LocalSpelling(File("src/main/res/raw/wordlist.txt").readLines().take(10_000))

    private fun learned(original: String, corrected: String) =
        LearnedRuleFilter.learnablePairs(original, corrected, spelling.isLoaded, spelling::isKnownWord)

    /** The extractor that shipped before KEYBOARD-008, kept to show what it learned. */
    private fun oldExtractor(original: String, corrected: String): List<Pair<String, String>> {
        val strip = "[^a-zA-Z]".toRegex()
        val o = original.split("\\s+".toRegex()).map { it.replace(strip, "").lowercase() }
        val c = corrected.split("\\s+".toRegex()).map { it.replace(strip, "").lowercase() }
        if (o.size != c.size) return emptyList()
        return o.indices.filter { o[it].isNotEmpty() && c[it].isNotEmpty() && o[it] != c[it] && o[it].length > 2 }
            .map { o[it] to c[it] }
    }

    private val grammarFixes = listOf(
        "your going to love it" to "you're going to love it",
        "there car is red" to "their car is red",
        "where you there" to "were you there",
        "they goes home" to "they go home",
        "we was there" to "we were there",
        "bigger then me" to "bigger than me",
        "less people came" to "fewer people came",
        "who's car is this" to "whose car is this",
        "I seen it yesterday" to "I saw it yesterday",
        "the affect was big" to "the effect was big"
    )

    @Test
    fun `ten grammar fixes learn no rules`() {
        grammarFixes.forEach { (before, after) ->
            assertEquals(before, emptyList<Pair<String, String>>(), learned(before, after))
        }
        // The old extractor turned every one of these into a permanent rule.
        assertEquals(10, grammarFixes.count { (before, after) -> oldExtractor(before, after).isNotEmpty() })
        assertEquals(listOf("your" to "youre"), oldExtractor("your going", "you're going"))
    }

    @Test
    fun `real misspellings are still learned, ignoring punctuation and keeping apostrophes`() {
        assertEquals(
            listOf("teh" to "the", "definately" to "definitely"),
            learned("teh cat sat, definately.", "the cat sat, definitely.")
        )
        assertEquals(listOf("tomorow" to "tomorrow"), learned("(tomorow)", "(tomorrow)"))
        assertEquals(listOf("dont" to "don't"), learned("I dont know", "I don’t know"))
    }

    @Test
    fun `nothing is learned before the dictionary loads or for big rewrites`() {
        assertTrue(LearnedRuleFilter.learnablePairs("teh", "the", false, spelling::isKnownWord).isEmpty())
        assertFalse(LocalSpelling(listOf("the", "cat")).isLoaded)
        assertTrue(learned("wrking late", "travelling late").isEmpty())
        assertTrue(learned("teh dog", "a dog barked").isEmpty())
    }

    @Test
    fun `stripped contractions are not real words but ordinary words are`() {
        listOf("dont", "im", "thats", "cant").forEach { assertFalse(it, spelling.isKnownWord(it)) }
        listOf("its", "were", "well", "your", "there").forEach { assertTrue(it, spelling.isKnownWord(it)) }
    }

    @Test
    fun `harmful stored rules are recognised`() {
        val harmful = listOf("your" to "youre", "there" to "their", "its" to "it's", "should" to "have", "was" to "were")
        val good = listOf("teh" to "the", "tomorow" to "tomorrow", "definately" to "definitely", "dont" to "don't")
        harmful.forEach { (t, c) -> assertTrue("$t→$c", LearnedRuleFilter.isHarmful(t, c, spelling::isKnownWord)) }
        good.forEach { (t, c) -> assertFalse("$t→$c", LearnedRuleFilter.isHarmful(t, c, spelling::isKnownWord)) }
    }

    @Test
    fun `learned rule applies before trailing punctuation and keeps it`() {
        val rules = listOf(LearnedCorrection(typo = "teh", correction = "the"))
        fun resolve(word: String) = WordCommitResolver.resolve(word, emptyList(), rules, false, { TextExpansion.ExpandedText(it) })
        assertEquals(WordReplacement("the,", fromLearnedRule = true), resolve("teh,"))
        assertEquals(WordReplacement("the.", fromLearnedRule = true), resolve("teh."))
        assertEquals(WordReplacement("The?!", fromLearnedRule = true), resolve("Teh?!"))
        assertEquals(WordReplacement("the", fromLearnedRule = true), resolve("teh"))
        assertEquals(null, resolve(","))
    }
}
