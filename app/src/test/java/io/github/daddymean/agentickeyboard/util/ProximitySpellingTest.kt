package io.github.daddymean.agentickeyboard.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KEYBOARD-019: keyboard-aware spelling suggestions, measured on the words Keith
 * actually mistyped on 2026-10-08 (22:11 and 22:14 PT) with the shipped dictionary.
 */
class ProximitySpellingTest {
    private val words = File("src/main/res/raw/wordlist.txt").readLines().map { it.trim() }.filter { it.isNotEmpty() }
    private val spelling = LocalSpelling(words.take(10_000))

    private val keithWords = listOf(
        "aure" to "sure", "thw" to "the", "arw" to "are", "reaulting" to "resulting", "errora" to "errors",
        "spellimng" to "spelling", "sensitiviry" to "sensitivity", "smal" to "small", "largw" to "large",
        "ginf" to "going", "corrwcr" to "correct", "speelng" to "spelling", "maube" to "maybe", "an" to "can",
        "diacwrn" to "discern", "reasnng" to "reasoning"
    )

    @After fun tearDown() = TapTrail.clear()

    private fun firstChip(typo: String, proximity: Boolean) =
        spelling.predictiveSuggestions(typo, emptyList(), null, proximity = proximity).firstOrNull()

    @Test fun `Keith's mistyped words get the right word first in the strip`() {
        val fixed = keithWords.count { (typo, meant) -> firstChip(typo, proximity = true).equals(meant, ignoreCase = true) }
        val before = keithWords.count { (typo, meant) -> firstChip(typo, proximity = false).equals(meant, ignoreCase = true) }
        assertEquals("before KEYBOARD-019 the strip fixed 8 of 16", 8, before)
        assertTrue("fixed $fixed of 16: ${keithWords.map { it.first to firstChip(it.first, true) }}", fixed >= 13)
    }

    @Test fun `common slang is never offered a correction`() {
        for (word in LocalSpelling.COMMON_SLANG) {
            val chips = spelling.predictiveSuggestions(word, emptyList(), null, proximity = true)
            assertTrue("$word -> $chips", chips.all { it.lowercase().startsWith(word) })
        }
    }

    @Test fun `the user's own words are never offered a correction`() {
        val chips = spelling.predictiveSuggestions("keithb", listOf("keithb", "agentic"), null, proximity = true)
        assertEquals(emptyList<String>(), chips)
    }

    @Test fun `with proximity off (sensitive fields) the strip behaves as before`() {
        // The previous one-edit, frequency-ranked guesses (#149).
        assertEquals(listOf("are"), spelling.predictiveSuggestions("aure", emptyList(), null, proximity = false))
        assertEquals(listOf("gif"), spelling.predictiveSuggestions("ginf", emptyList(), null, proximity = false))
        assertEquals(emptyList<String>(), spelling.predictiveSuggestions("corrwcr", emptyList(), null, proximity = false))
        assertEquals(listOf("are"), spelling.predictiveSuggestions("aure", emptyList(), null))
    }

    @Test fun `where the finger landed decides between two neighbours`() {
        // d sits between s and f; "fun" is the more frequent word.
        val small = LocalSpelling(listOf("fun", "sun"))
        fun chip(taps: List<TapOffset>?) =
            small.predictiveSuggestions("dun", emptyList(), null, proximity = true, taps = taps).first()
        val centre = TapOffset(0f, 0f)
        assertEquals("fun", chip(null))
        assertEquals("sun", chip(listOf(TapOffset(-0.45f, 0f), centre, centre)))
        assertEquals("fun", chip(listOf(TapOffset(0.45f, 0f), centre, centre)))
    }

    @Test fun `the tap trail only answers for the letters just tapped`() {
        val centre = TapOffset(0f, 0f)
        "thw".forEach { TapTrail.record(it, centre) }
        assertEquals(3, TapTrail.offsetsFor("thw")?.size)
        assertEquals(2, TapTrail.offsetsFor("hw")?.size)
        assertNull(TapTrail.offsetsFor("the"))
        TapTrail.clear()
        assertNull(TapTrail.offsetsFor("thw"))
    }

    @Test fun `a correction is fast enough to run on every keystroke`() {
        val typos = keithWords.map { it.first }
        repeat(3) { typos.forEach { spelling.predictiveSuggestions(it, emptyList(), null, proximity = true) } }
        val start = System.nanoTime()
        repeat(5) { typos.forEach { spelling.predictiveSuggestions(it, emptyList(), null, proximity = true) } }
        val perWordMs = (System.nanoTime() - start) / 1e6 / (5 * typos.size)
        assertTrue("took $perWordMs ms per word", perWordMs < 50.0)
    }
}
