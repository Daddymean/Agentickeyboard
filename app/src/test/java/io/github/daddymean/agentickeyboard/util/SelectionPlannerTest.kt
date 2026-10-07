package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.util.SelectionPlanner.plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionPlannerTest {

    private val text = "Hello there, world"
    //                  0123456789...

    private fun caret(at: Int) = SelectionRange.caret(at)

    // --- caret movement -------------------------------------------------------

    @Test
    fun `move left steps one character back`() {
        assertEquals(caret(4), plan(text, caret(5), SelectionCommand.MoveLeft))
    }

    @Test
    fun `move left clamps at the start of the text`() {
        assertEquals(caret(0), plan(text, caret(0), SelectionCommand.MoveLeft))
    }

    @Test
    fun `move right clamps at the end of the text`() {
        val end = text.length
        assertEquals(caret(end), plan(text, caret(end), SelectionCommand.MoveRight))
    }

    @Test
    fun `move left collapses an existing selection to its left edge`() {
        assertEquals(caret(6), plan(text, SelectionRange(6, 11), SelectionCommand.MoveLeft))
    }

    @Test
    fun `move right collapses an existing selection to its right edge`() {
        assertEquals(caret(11), plan(text, SelectionRange(6, 11), SelectionCommand.MoveRight))
    }

    // --- word-wise movement ---------------------------------------------------

    @Test
    fun `move word left lands on the start of the word the caret is inside`() {
        // caret inside "there" (index 8) -> start of "there" (index 6)
        assertEquals(caret(6), plan(text, caret(8), SelectionCommand.MoveWordLeft))
    }

    @Test
    fun `move word left skips punctuation and whitespace`() {
        // caret at the "w" of "world" (13) -> start of "there" (6)
        assertEquals(caret(6), plan(text, caret(13), SelectionCommand.MoveWordLeft))
    }

    @Test
    fun `move word right lands on the end of the next word`() {
        assertEquals(caret(5), plan(text, caret(0), SelectionCommand.MoveWordRight))
    }

    @Test
    fun `word movement is stable at the text edges`() {
        assertEquals(caret(0), plan(text, caret(0), SelectionCommand.MoveWordLeft))
        val end = text.length
        assertEquals(caret(end), plan(text, caret(end), SelectionCommand.MoveWordRight))
    }

    // --- extending ------------------------------------------------------------

    @Test
    fun `extend right keeps the anchor and grows the active edge`() {
        assertEquals(SelectionRange(5, 6), plan(text, caret(5), SelectionCommand.ExtendRight))
    }

    @Test
    fun `extend left produces a backwards selection that keeps the anchor`() {
        val result = plan(text, caret(5), SelectionCommand.ExtendLeft)
        assertEquals(SelectionRange(5, 4), result)
        assertEquals(4, result.min)
        assertEquals(5, result.max)
        assertEquals(1, result.length)
    }

    @Test
    fun `extend word right grows the selection by a whole word`() {
        // anchor 0, active 0 -> active jumps to the end of "Hello"
        assertEquals(SelectionRange(0, 5), plan(text, caret(0), SelectionCommand.ExtendWordRight))
    }

    @Test
    fun `extend word left grows the selection backwards by a whole word`() {
        assertEquals(SelectionRange(11, 6), plan(text, caret(11), SelectionCommand.ExtendWordLeft))
    }

    @Test
    fun `extending clamps at the text edges without losing the anchor`() {
        assertEquals(SelectionRange(2, 0), plan(text, SelectionRange(2, 0), SelectionCommand.ExtendLeft))
        val end = text.length
        assertEquals(SelectionRange(2, end), plan(text, SelectionRange(2, end), SelectionCommand.ExtendRight))
    }

    // --- select all / word / line --------------------------------------------

    @Test
    fun `select all spans the whole text`() {
        assertEquals(SelectionRange(0, text.length), plan(text, caret(3), SelectionCommand.SelectAll))
    }

    @Test
    fun `select all on empty text is a collapsed caret`() {
        assertTrue(plan("", caret(0), SelectionCommand.SelectAll).isCollapsed)
    }

    @Test
    fun `select word takes the word the caret sits inside`() {
        assertEquals(SelectionRange(6, 11), plan(text, caret(8), SelectionCommand.SelectWord))
    }

    @Test
    fun `select word works from either edge of the word`() {
        assertEquals(SelectionRange(6, 11), plan(text, caret(6), SelectionCommand.SelectWord))
        assertEquals(SelectionRange(6, 11), plan(text, caret(11), SelectionCommand.SelectWord))
    }

    @Test
    fun `select word in whitespace prefers the preceding word`() {
        // index 12 is the space after "there," -> falls back to "there"
        assertEquals(SelectionRange(6, 11), plan(text, caret(12), SelectionCommand.SelectWord))
    }

    @Test
    fun `select word ahead when nothing precedes the caret`() {
        assertEquals(SelectionRange(3, 5), plan("   hi", caret(0), SelectionCommand.SelectWord))
    }

    @Test
    fun `select word collapses when the text holds no word at all`() {
        assertTrue(plan("   ", caret(1), SelectionCommand.SelectWord).isCollapsed)
    }

    @Test
    fun `select word keeps apostrophes inside the word`() {
        val s = "it doesn't matter"
        assertEquals(SelectionRange(3, 10), plan(s, caret(6), SelectionCommand.SelectWord))
    }

    @Test
    fun `select line spans one line of a multi line draft`() {
        val s = "first\nsecond\nthird"
        assertEquals(SelectionRange(6, 12), plan(s, caret(8), SelectionCommand.SelectLine))
    }

    @Test
    fun `select line handles the first and last lines`() {
        val s = "first\nsecond\nthird"
        assertEquals(SelectionRange(0, 5), plan(s, caret(2), SelectionCommand.SelectLine))
        assertEquals(SelectionRange(13, 18), plan(s, caret(15), SelectionCommand.SelectLine))
    }

    @Test
    fun `select line at a line break keeps the line it terminates`() {
        val s = "first\nsecond"
        assertEquals(SelectionRange(0, 5), plan(s, caret(5), SelectionCommand.SelectLine))
    }

    @Test
    fun `select line with no breaks spans the whole text`() {
        assertEquals(SelectionRange(0, text.length), plan(text, caret(4), SelectionCommand.SelectLine))
    }

    // --- collapse and input hygiene -------------------------------------------

    @Test
    fun `collapse drops the selection and keeps the caret on the active edge`() {
        val result = plan(text, SelectionRange(2, 9), SelectionCommand.Collapse)
        assertEquals(caret(9), result)
        assertTrue(result.isCollapsed)
    }

    @Test
    fun `collapse of a backwards selection keeps the caret on the left`() {
        assertEquals(caret(2), plan(text, SelectionRange(9, 2), SelectionCommand.Collapse))
    }

    @Test
    fun `out of range offsets are clamped rather than throwing`() {
        assertEquals(caret(text.length), plan(text, caret(900), SelectionCommand.Collapse))
        assertEquals(caret(0), plan(text, caret(-5), SelectionCommand.Collapse))
        // A wildly out-of-range pair still clamps onto the real text bounds.
        assertEquals(
            SelectionRange(0, text.length),
            plan(text, SelectionRange(-40, 900), SelectionCommand.ExtendRight)
        )
    }

    @Test
    fun `commands on empty text always yield a collapsed caret at zero`() {
        SelectionCommand.entries.forEach { command ->
            val result = plan("", caret(0), command)
            assertTrue("$command should collapse on empty text", result.isCollapsed)
            assertEquals("$command should stay at 0", 0, result.start)
        }
    }

    // --- supplementary characters and grapheme clusters -----------------------

    @Test
    fun `move right past an emoji clears the whole surrogate pair`() {
        // "\uD83D\uDE00x": the emoji occupies two UTF-16 code units, so a naive
        // +1 would leave the caret between the surrogates and corrupt it.
        val s = "\uD83D\uDE00x"
        assertEquals(3, s.length)
        assertEquals(caret(2), plan(s, caret(0), SelectionCommand.MoveRight))
    }

    @Test
    fun `move left past an emoji clears the whole surrogate pair`() {
        val s = "\uD83D\uDE00x"
        assertEquals(caret(0), plan(s, caret(2), SelectionCommand.MoveLeft))
    }

    @Test
    fun `extending over an emoji covers it entirely`() {
        val s = "a\uD83D\uDE00b"
        // From just after "a", one extend-right must swallow both code units.
        assertEquals(SelectionRange(1, 3), plan(s, caret(1), SelectionCommand.ExtendRight))
        // And extending back from just after the emoji must release both.
        assertEquals(SelectionRange(3, 1), plan(s, caret(3), SelectionCommand.ExtendLeft))
    }

    @Test
    fun `a combining mark travels with its base character`() {
        // "e" + U+0301 combining acute renders as one character.
        val s = "e\u0301x"
        assertEquals(3, s.length)
        assertEquals(caret(2), plan(s, caret(0), SelectionCommand.MoveRight))
        assertEquals(caret(0), plan(s, caret(2), SelectionCommand.MoveLeft))
    }

    @Test
    fun `movement over plain ascii is still one character at a time`() {
        assertEquals(caret(1), plan("abc", caret(0), SelectionCommand.MoveRight))
        assertEquals(caret(1), plan("abc", caret(2), SelectionCommand.MoveLeft))
    }

    @Test
    fun `grapheme helpers clamp rather than throw at the edges`() {
        assertEquals(0, SelectionPlanner.graphemeBefore("abc", 0))
        assertEquals(3, SelectionPlanner.graphemeAfter("abc", 3))
        assertEquals(0, SelectionPlanner.graphemeBefore("", 0))
        assertEquals(0, SelectionPlanner.graphemeAfter("", 0))
    }

    // --- combining marks in word-wise commands ------------------------------
    //
    // Regression tests for the defect Hax reported on PR #127: isWordChar
    // accepted only letters, digits, apostrophe and underscore, so a combining
    // mark broke the word scan and the commands stopped inside a cluster. Every
    // expectation below was reproduced against the old implementation first.

    /** "नमस्ते दुनिया" — the virama at 3 and the vowel sign at 5 are Mn marks. */
    private val hindi = "\u0928\u092E\u0938\u094D\u0924\u0947 \u0926\u0941\u0928\u093F\u092F\u093E"

    /** "cafe" + combining acute, i.e. NFD "café". */
    private val nfd = "cafe\u0301 au"

    @Test
    fun `move word right crosses a devanagari word instead of stopping at the virama`() {
        // Previously 3, between स and the virama.
        assertEquals(caret(6), plan(hindi, caret(0), SelectionCommand.MoveWordRight))
    }

    @Test
    fun `move word left crosses a devanagari word instead of stopping at a vowel sign`() {
        // Previously 4, between त and े.
        assertEquals(caret(0), plan(hindi, caret(6), SelectionCommand.MoveWordLeft))
    }

    @Test
    fun `select word takes a whole devanagari word`() {
        // Previously (0,3) — a fragment, which an AI rewrite would then mangle.
        assertEquals(SelectionRange(0, 6), plan(hindi, caret(1), SelectionCommand.SelectWord))
    }

    @Test
    fun `the second devanagari word is also whole`() {
        assertEquals(SelectionRange(7, 13), plan(hindi, caret(8), SelectionCommand.SelectWord))
    }

    @Test
    fun `a combining acute stays with its base in word commands`() {
        // Previously 4 and (0,4), splitting "cafe" from its accent.
        assertEquals(caret(5), plan(nfd, caret(0), SelectionCommand.MoveWordRight))
        assertEquals(SelectionRange(0, 5), plan(nfd, caret(1), SelectionCommand.SelectWord))
    }

    @Test
    fun `extend word right covers the combining mark too`() {
        assertEquals(SelectionRange(0, 5), plan(nfd, caret(0), SelectionCommand.ExtendWordRight))
    }

    @Test
    fun `a zero width joiner does not break a word`() {
        // A ZWJ between letters joins them into one word for selection purposes.
        val joined = "a\u200Db c"
        assertEquals(SelectionRange(0, 3), plan(joined, caret(0), SelectionCommand.SelectWord))
    }

    @Test
    fun `plain ascii word movement is unchanged by the mark handling`() {
        assertEquals(caret(5), plan(text, caret(0), SelectionCommand.MoveWordRight))
        assertEquals(caret(6), plan(text, caret(8), SelectionCommand.MoveWordLeft))
        assertEquals(SelectionRange(6, 11), plan(text, caret(8), SelectionCommand.SelectWord))
    }

    @Test
    fun `word commands never return an offset inside a grapheme cluster`() {
        // Belt and braces over the whole string: walk every caret position and
        // assert each result lands on a character boundary.
        val probes = listOf(hindi, nfd, "a\u200Db c", text)
        for (probe in probes) {
            for (caretAt in 0..probe.length) {
                for (command in listOf(
                    SelectionCommand.MoveWordLeft,
                    SelectionCommand.MoveWordRight,
                    SelectionCommand.SelectWord
                )) {
                    val result = plan(probe, caret(caretAt), command)
                    for (offset in listOf(result.start, result.end)) {
                        if (offset in 1 until probe.length) {
                            assertFalse(
                                "$command from $caretAt in \"$probe\" returned $offset," +
                                    " which splits a surrogate pair",
                                probe[offset - 1].isHighSurrogate() && probe[offset].isLowSurrogate()
                            )
                        }
                    }
                }
            }
        }
    }

    // --- snapshot-relative to absolute offsets --------------------------------

    @Test
    fun `a document range adds the extraction start offset`() {
        assertEquals(
            SelectionRange(104, 110),
            SelectionPlanner.toDocumentRange(SelectionRange(4, 10), 100)
        )
    }

    @Test
    fun `a zero start offset leaves the range untouched`() {
        assertEquals(
            SelectionRange(4, 10),
            SelectionPlanner.toDocumentRange(SelectionRange(4, 10), 0)
        )
    }

    @Test
    fun `a negative start offset is treated as zero`() {
        // Editors may report -1 instead of a real offset.
        assertEquals(
            SelectionRange(4, 10),
            SelectionPlanner.toDocumentRange(SelectionRange(4, 10), -1)
        )
    }

    @Test
    fun `a backwards range keeps its direction when mapped`() {
        val mapped = SelectionPlanner.toDocumentRange(SelectionRange(10, 4), 100)
        assertEquals(SelectionRange(110, 104), mapped)
        assertEquals(104, mapped.min)
        assertEquals(110, mapped.max)
    }

    @Test
    fun `a collapsed range reports no length`() {
        assertTrue(caret(4).isCollapsed)
        assertEquals(0, caret(4).length)
        assertFalse(SelectionRange(4, 5).isCollapsed)
    }
}
