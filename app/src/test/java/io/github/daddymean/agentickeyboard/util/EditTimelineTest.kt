package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** LAB-014: the in-memory draft timeline behind Rewind. */
class EditTimelineTest {

    private fun EditTimeline.type(s: String, from: String = "") {
        var t = from
        for (c in s) { t += c; observe(t) }
    }

    @Test
    fun keepsTheStateBeforeADeletionBurst() {
        val tl = EditTimeline()
        tl.type("See you at the park tomorrow")
        var t = "See you at the park tomorrow"
        repeat(9) { t = t.dropLast(1); tl.observe(t) } // delete "tomorrow" and the space
        assertEquals("See you at the park", t.trimEnd())
        val v = tl.versions()
        assertEquals("See you at the park tomorrow", v.first().text)
        assertEquals("+ tomorrow", v.first().label)
    }

    @Test
    fun checkpointsAtWordBoundariesNotEveryKeystroke() {
        val tl = EditTimeline()
        tl.type("one two three four")
        // "one ", "one two ", "one two three " -> 3 checkpoints, not 18.
        assertEquals(3, tl.size)
    }

    @Test
    fun explicitCheckpointBeforeAReplacementCanBeRestored() {
        val tl = EditTimeline()
        tl.type("pls send teh file")
        tl.checkpoint()
        tl.observe("Please send the file.")
        val v = tl.versions()
        assertEquals("pls send teh file", v.first().text)
        assertTrue(v.first().label, v.first().label.contains("→"))
    }

    @Test
    fun capsHistoryAndLength() {
        val tl = EditTimeline(maxCheckpoints = 5, maxChars = 50)
        tl.type("a b c d e f g h i j ")
        assertEquals(5, tl.size)
        tl.observe("x".repeat(51))
        assertEquals(0, tl.size)
        assertTrue(tl.versions().isEmpty())
    }

    @Test
    fun versionsSkipDuplicatesAndTheCurrentText() {
        val tl = EditTimeline()
        tl.type("hi ")
        tl.checkpoint(); tl.checkpoint()
        assertTrue(tl.versions().none { it.text == "hi " })
    }

    @Test
    fun clearForgetsEverything() {
        val tl = EditTimeline()
        tl.type("secret plans ")
        tl.clear()
        assertEquals(0, tl.size)
        assertTrue(tl.versions().isEmpty())
    }

    @Test
    fun observeIsCheapOnTheKeystrokePath() {
        val base = "The quick brown fox jumps over the lazy dog. ".repeat(20).take(900)
        val tl = EditTimeline()
        repeat(2000) { tl.observe(base.take(800 + it % 100)) } // warm-up
        val n = 20_000
        val t0 = System.nanoTime()
        for (i in 0 until n) tl.observe(base.take(800 + i % 100))
        val ns = (System.nanoTime() - t0).toDouble() / n
        println("EditTimeline.observe on ~850 chars: %.0f ns/keystroke".format(ns))
        assertTrue("observe took $ns ns", ns < 200_000)
    }
}
