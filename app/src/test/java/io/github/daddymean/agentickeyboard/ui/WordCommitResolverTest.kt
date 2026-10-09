package io.github.daddymean.agentickeyboard.ui

import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.util.TextExpansion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** KEYBOARD-002: seeded rules, so a broken pause guard fails these tests. */
class WordCommitResolverTest {

    private val shortcuts = listOf(ShortcutTemplate(shortcut = "brb", template = "be right back"))
    private val corrections = listOf(LearnedCorrection(typo = "teh", correction = "the"))
    private val expand: (String) -> TextExpansion.ExpandedText = { TextExpansion.ExpandedText(it) }

    private fun resolve(word: String, paused: Boolean) =
        WordCommitResolver.resolve(word, shortcuts, corrections, paused, expand)

    @Test
    fun `learned correction applies, stops while paused, and returns on resume`() {
        assertEquals(WordReplacement("the", fromLearnedRule = true), resolve("teh", paused = false))
        assertNull(resolve("teh", paused = true))
        assertEquals(WordReplacement("the", fromLearnedRule = true), resolve("teh", paused = false))
    }

    @Test
    fun `shortcut expands before pause, during pause and after resume`() {
        val expected = WordReplacement("be right back", fromLearnedRule = false)
        listOf(false, true, false).forEach { paused ->
            assertEquals("paused=$paused", expected, resolve("brb", paused))
        }
    }

    @Test
    fun `capitalised typo keeps its capital when not paused`() {
        assertEquals(WordReplacement("The", fromLearnedRule = true), resolve("Teh", paused = false))
        assertNull(resolve("Teh", paused = true))
    }

    @Test
    fun `shortcut caret offset is carried through`() {
        val withCaret: (String) -> TextExpansion.ExpandedText = { TextExpansion.ExpandedText("Hi !", cursorOffset = 3) }
        val result = WordCommitResolver.resolve("brb", shortcuts, corrections, true, withCaret)
        assertEquals(WordReplacement("Hi !", fromLearnedRule = false, cursorOffset = 3), result)
    }

    // --- KEYBOARD-006: sensitive (password/incognito) fields --------------------

    private fun resolveIn(word: String, sensitive: Boolean, paused: Boolean = false) =
        WordCommitResolver.resolve(word, shortcuts, corrections, paused, expand, sensitiveField = sensitive)

    @Test
    fun `sensitive field keeps learned typos and shortcuts exactly as typed`() {
        assertNull(resolveIn("teh", sensitive = true))
        assertNull(resolveIn("Teh", sensitive = true))
        assertNull(resolveIn("brb", sensitive = true))
        assertNull(resolveIn("brb", sensitive = true, paused = true))
    }

    @Test
    fun `sensitive field never asks for a template expansion`() {
        var expansions = 0
        val counting: (String) -> TextExpansion.ExpandedText = { expansions++; TextExpansion.ExpandedText(it) }
        assertNull(WordCommitResolver.resolve("brb", shortcuts, corrections, false, counting, sensitiveField = true))
        assertEquals(0, expansions)
    }

    @Test
    fun `ordinary field behavior is unchanged by the sensitive rule`() {
        assertEquals(WordReplacement("the", fromLearnedRule = true), resolveIn("teh", sensitive = false))
        assertEquals(WordReplacement("be right back", fromLearnedRule = false), resolveIn("brb", sensitive = false))
        // Default argument keeps existing callers on the ordinary path.
        assertEquals(WordReplacement("the", fromLearnedRule = true), resolve("teh", paused = false))
    }
}
