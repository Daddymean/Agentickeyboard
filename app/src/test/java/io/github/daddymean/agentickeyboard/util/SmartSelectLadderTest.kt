package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** LAB-011: the semantic smart-select ladder. */
class SmartSelectLadderTest {

    private fun rungs(text: String, caretMarker: String = "|"): List<Pair<SmartUnit, String>> {
        val at = text.indexOf(caretMarker)
        val clean = text.removeRange(at, at + caretMarker.length)
        return SmartSelectLadder.ladder(clean, at, Locale.US).map { it.unit to clean.substring(it.range.min, it.range.max) }
    }

    @Test
    fun climbsWordClauseSentenceParagraphAll() {
        val r = rungs("Hi there. If it rains, we st|ay home. Bye.\nNext line")
        assertEquals(
            listOf(
                SmartUnit.Word to "stay",
                SmartUnit.Clause to "we stay home.",
                SmartUnit.Sentence to "If it rains, we stay home.",
                SmartUnit.Paragraph to "Hi there. If it rains, we stay home. Bye.",
                SmartUnit.All to "Hi there. If it rains, we stay home. Bye.\nNext line"
            ),
            r
        )
    }

    @Test
    fun grabsAWholeLinkBetweenWordAndSentence() {
        val r = rungs("Read https://example.com/do|cs/page?x=1 now")
        assertEquals(SmartUnit.Word to "docs", r[0])
        assertEquals(SmartUnit.Link to "https://example.com/docs/page?x=1", r[1])
    }

    @Test
    fun linkDropsTrailingSentencePunctuation() {
        val r = rungs("See www.exam|ple.org.")
        assertEquals(SmartUnit.Link to "www.example.org", r[1])
    }

    @Test
    fun grabsEmailPhoneHandleAmountAndTime() {
        assertEquals(SmartUnit.Email to "keith.b@mail.example.com", rungs("mail keith.b@ma|il.example.com today")[1])
        assertEquals(SmartUnit.Phone to "+1 (415) 555-0134", rungs("call +1 (415) 55|5-0134 ok")[1])
        assertEquals(SmartUnit.Handle to "#agent_board", rungs("post in #agent_bo|ard please")[1])
        assertEquals(SmartUnit.Amount to "$1,250.50", rungs("it cost $1,2|50.50 total")[1])
        assertEquals(SmartUnit.Amount to "12.5 km", rungs("ran 12|.5 km today")[1])
        assertEquals(SmartUnit.Time to "3:30 pm PT", rungs("meet at 3:3|0 pm PT tomorrow")[1])
    }

    @Test
    fun selectsInsideQuotesAndBrackets() {
        assertEquals(SmartUnit.Quote to "the quick fox", rungs("he said \"the qu|ick fox\" twice")[1])
        assertEquals(SmartUnit.Quote to "see the docs", rungs("Fine (see the d|ocs) then")[1])
        // Innermost pair wins.
        assertEquals(SmartUnit.Quote to "b c", rungs("x (a [b |c] d) y")[1])
    }

    @Test
    fun everyRungStrictlyContainsThePrevious() {
        val samples = listOf(
            "a", "  ", "one two three", "Mail me: a@b.co, or (call 555-123-4567). Thanks!",
            "\"quoted\" start", "emoji 👍🏽 text, more 🎉.\n\nsecond para", "trailing space   "
        )
        for (text in samples) for (caret in 0..text.length) {
            val steps = SmartSelectLadder.ladder(text, caret, Locale.US)
            for (i in 1 until steps.size) {
                val a = steps[i - 1].range
                val b = steps[i].range
                assertTrue("$text@$caret $a -> $b", b.min <= a.min && b.max >= a.max && b.length > a.length)
            }
            steps.forEach { assertTrue(it.range.min >= 0 && it.range.max <= text.length) }
        }
    }

    @Test
    fun emptyTextHasNoLadder() {
        assertTrue(SmartSelectLadder.ladder("", 0).isEmpty())
    }

    @Test
    fun hugeTextSkipsTheEntityScanButStillClimbs() {
        val big = "word ".repeat(SmartSelectLadder.MAX_SCAN_CHARS / 5 + 10) + "https://x.io"
        val steps = SmartSelectLadder.ladder(big, 2, Locale.US)
        assertEquals(SmartUnit.Word, steps.first().unit)
        assertEquals(big.length, steps.last().range.max)
        assertTrue(steps.none { it.unit == SmartUnit.Link })
    }

    @Test
    fun ladderIsFastEnoughForAHold() {
        val text = ("Meet at 3pm PT, see https://example.com/a or mail a@b.co. " .repeat(60)).trim()
        repeat(200) { SmartSelectLadder.ladder(text, text.length / 2, Locale.US) } // warm-up
        val n = 500
        val t0 = System.nanoTime()
        repeat(n) { SmartSelectLadder.ladder(text, (it * 37) % text.length, Locale.US) }
        val perCallMicros = (System.nanoTime() - t0) / 1000.0 / n
        println("SmartSelectLadder.ladder on ${text.length} chars: %.1f µs/call".format(perCallMicros))
        assertTrue("ladder took $perCallMicros µs", perCallMicros < 16_000)
    }
}

class SmartSelectSessionTest {
    @Test
    fun climbsClampsAndReturnsToTheCaret() {
        val text = "Call me at 555-123-4567 tonight."
        val caret = text.indexOf("123")
        val steps = SmartSelectLadder.ladder(text, caret, Locale.US)
        val session = SmartSelectSession(steps, startOffset = 100, origin = SelectionRange.caret(100 + caret))
        assertEquals("Word", session.label)
        assertEquals(SelectionRange(100 + text.indexOf("123"), 100 + text.indexOf("123") + 3), session.documentRange)
        assertTrue(session.move(1))
        assertEquals("Phone", session.label)
        assertTrue(session.move(10))
        // One sentence fills the field, so the sentence is the top rung.
        assertEquals("Sentence", session.label)
        assertEquals(false, session.move(1))
        assertTrue(session.move(-100))
        assertEquals(SmartSelectSession.CARET_LABEL, session.label)
        assertEquals(SelectionRange.caret(100 + caret), session.documentRange)
    }
}
