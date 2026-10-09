package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.util.SelectionPlanner.plan
import java.text.BreakIterator
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

    // --- supplementary-plane scripts -----------------------------------------
    //
    // Codex's follow-up on #130: classifying per Char cannot see above the BMP,
    // because each half of a surrogate pair has category Cs — never a letter and
    // never a mark. Word scanning is therefore by code point.

    /** Two Adlam letters followed by Adlam Alif Lengthener, a supplementary Mn mark. */
    private val adlam = "\uD83A\uDD00\uD83A\uDD21\uD83A\uDD44"

    /** "ab" + MATHEMATICAL BOLD SMALL B + "cd" — styled text seen on social media. */
    private val mathBold = "ab\uD835\uDC1Bcd"

    @Test
    fun `a supplementary script word is found at all`() {
        assertEquals(6, adlam.length)
        // Per-Char classification found no word here, so SelectWord collapsed.
        assertEquals(SelectionRange(0, 6), plan(adlam, caret(0), SelectionCommand.SelectWord))
        assertEquals(caret(6), plan(adlam, caret(0), SelectionCommand.MoveWordRight))
    }

    @Test
    fun `a supplementary combining mark stays inside its word`() {
        // The Adlam mark is the last two units; the word must not stop before it.
        assertEquals(SelectionRange(0, 6), plan(adlam, caret(2), SelectionCommand.SelectWord))
    }

    @Test
    fun `a surrogate pair mid word does not stop the scan`() {
        assertEquals(6, mathBold.length)
        // Previously 2: the scan halted at the surrogate pair, mid-word.
        assertEquals(caret(6), plan(mathBold, caret(0), SelectionCommand.MoveWordRight))
        assertEquals(SelectionRange(0, 6), plan(mathBold, caret(0), SelectionCommand.SelectWord))
        assertEquals(caret(0), plan(mathBold, caret(6), SelectionCommand.MoveWordLeft))
    }

    @Test
    fun `word scanning never moves an edge inside a surrogate pair`() {
        // The property asserted is about the *moving* edge. An Extend command
        // preserves its anchor verbatim, which is correct — the anchor is where
        // the user's selection started — so feeding this sweep a caret that is
        // itself mid-pair gets that same offset back as the anchor. A real editor
        // never reports a mid-surrogate selection, and snapping the anchor would
        // silently relocate legitimate caret positions inside Indic clusters, so
        // the anchor is left alone and exempted here by identity rather than by
        // loosening the check.
        val probes = listOf(adlam, mathBold, "x\uD835\uDC1B y", hindi)
        for (probe in probes) {
            fun splitsAPair(offset: Int) = offset in 1 until probe.length &&
                probe[offset - 1].isHighSurrogate() && probe[offset].isLowSurrogate()

            for (caretAt in 0..probe.length) {
                for (command in listOf(
                    SelectionCommand.MoveWordLeft,
                    SelectionCommand.MoveWordRight,
                    SelectionCommand.SelectWord,
                    SelectionCommand.ExtendWordLeft,
                    SelectionCommand.ExtendWordRight
                )) {
                    val result = plan(probe, caret(caretAt), command)
                    assertFalse(
                        "$command from $caretAt in \"$probe\" moved its edge to" +
                            " ${result.end}, splitting a surrogate pair",
                        splitsAPair(result.end)
                    )
                    if (result.start != caretAt) {
                        assertFalse(
                            "$command from $caretAt computed a start of ${result.start}," +
                                " splitting a surrogate pair",
                            splitsAPair(result.start)
                        )
                    }
                }
            }
        }
    }

    // --- emoji and apostrophes ------------------------------------------------
    //
    // Hax's non-blocking notes on #130. Classifying a mark or joiner on its own
    // made emoji behave like words, because a ZWJ sequence is joiners between
    // pictographs and U+FE0F (the selector that renders "❤️" as an emoji) is
    // category Mn. An extender now counts only with a word base behind it. The
    // second note is older than the mark handling: U+2019 was no kind of word
    // character, so the apostrophe that iOS and macOS actually insert broke a
    // contraction in two.

    /** "❤️hello" — heart, variation selector, then a word with no space. */
    private val heartWord = "❤️hello"

    /** "hi 👨‍👩‍👧 there" — a three-person ZWJ family emoji between two words. */
    private val family = "hi 👨‍👩‍👧 there"

    /** "don’t stop" with U+2019, as iOS, macOS and word processors type it. */
    private val curly = "don’t stop"

    @Test
    fun `a variation selector does not pull an emoji into the next word`() {
        assertEquals(7, heartWord.length)
        // Previously (0, 7): the heart and its selector read as word characters.
        assertEquals(SelectionRange(2, 7), plan(heartWord, caret(0), SelectionCommand.SelectWord))
    }

    @Test
    fun `a zwj emoji sequence is skipped instead of walked as a word`() {
        assertEquals(17, family.length)
        assertEquals(caret(2), plan(family, caret(0), SelectionCommand.MoveWordRight))
        // Previously this returned 6: the scan skipped the first pictograph,
        // found the joiner at 5 word-worthy and consumed it alone. Under
        // Android's ICU iterator the same rule instead ran to 11, the end of the
        // whole cluster — Hax measured both. Either way the walk treated part of
        // an emoji as a word; now it carries on to "there".
        assertEquals(caret(17), plan(family, caret(2), SelectionCommand.MoveWordRight))
        assertEquals(caret(12), plan(family, caret(17), SelectionCommand.MoveWordLeft))
    }

    @Test
    fun `a caret stranded inside an emoji takes the word it left`() {
        // Offset 5 is the first joiner. There is no word there to select, so the
        // same whitespace fallback applies and "hi" wins over "there".
        assertEquals(SelectionRange(0, 2), plan(family, caret(5), SelectionCommand.SelectWord))
    }

    @Test
    fun `a curly apostrophe keeps a contraction in one piece`() {
        // Previously 3 and (0, 3) — "don", with the quote and "t" left behind.
        assertEquals(caret(5), plan(curly, caret(0), SelectionCommand.MoveWordRight))
        assertEquals(SelectionRange(0, 5), plan(curly, caret(2), SelectionCommand.SelectWord))
        assertEquals(caret(0), plan(curly, caret(5), SelectionCommand.MoveWordLeft))
    }

    @Test
    fun `a modifier letter apostrophe needs no special case`() {
        // U+02BC is category Lm, so isLetterOrDigit already accepts it. Asserted
        // so that nobody "fixes" it by adding a second constant.
        val modifier = "donʼt stop"
        assertEquals(SelectionRange(0, 5), plan(modifier, caret(2), SelectionCommand.SelectWord))
    }

    @Test
    fun `a mark with nothing in front of it is not a word`() {
        // A string opening with a combining acute — malformed, but editors do
        // hand it over. The mark has no base, so the word is "abc" alone.
        val orphan = "́abc"
        assertEquals(SelectionRange(1, 4), plan(orphan, caret(0), SelectionCommand.SelectWord))
        assertEquals(caret(1), plan(orphan, caret(3), SelectionCommand.MoveWordLeft))
    }

    @Test
    fun `an ascii apostrophe and underscore behave as they always did`() {
        // The base rule must not disturb the unconditional word characters.
        val mixed = "it's don't_x o'clock"
        assertEquals(caret(4), plan(mixed, caret(0), SelectionCommand.MoveWordRight))
        assertEquals(caret(12), plan(mixed, caret(4), SelectionCommand.MoveWordRight))
        assertEquals(caret(20), plan(mixed, caret(12), SelectionCommand.MoveWordRight))
        assertEquals(SelectionRange(5, 12), plan(mixed, caret(7), SelectionCommand.SelectWord))
    }

    @Test
    fun `word commands land on a character boundary in emoji and quoted text`() {
        // The earlier sweeps assert no split surrogate pair. This one asks the
        // iterator itself, so it also catches an offset wedged between a base
        // and its mark — the failure mode the variation selector introduced.
        val probes = listOf(heartWord, family, curly, "I ❤️ you", "́abc")
        for (probe in probes) {
            val iterator = BreakIterator.getCharacterInstance()
            iterator.setText(probe)
            for (caretAt in 0..probe.length) {
                for (command in listOf(
                    SelectionCommand.MoveWordLeft,
                    SelectionCommand.MoveWordRight,
                    SelectionCommand.SelectWord
                )) {
                    val result = plan(probe, caret(caretAt), command)
                    for (offset in listOf(result.start, result.end)) {
                        assertTrue(
                            "$command from $caretAt in \"$probe\" returned $offset," +
                                " which is not a character boundary",
                            iterator.isBoundary(offset)
                        )
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
    @Test(timeout = 5_000)
    fun longExtenderRunsRemainOneWordWithoutRepeatedBackwardScans() {
        val word = "a" + "\u0301".repeat(20_000)
        assertEquals(word.length, SelectionPlanner.nextWordBoundary(word, 0))
        assertEquals(0, SelectionPlanner.previousWordBoundary(word, word.length))
        assertEquals(SelectionRange(0, word.length), SelectionPlanner.selectWordAt(word, 10_000))
        val orphanMarks = "\u0301".repeat(20_000)
        assertEquals(SelectionRange.caret(10_000), SelectionPlanner.selectWordAt(orphanMarks, 10_000))
    }

}
