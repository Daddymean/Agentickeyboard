package io.github.daddymean.agentickeyboard.provenance

import io.github.daddymean.agentickeyboard.provenance.CompositionStats.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionStatsTest {

    @Test
    fun steadyRetypingReadsAsLowRevisionAndSteadyRhythm() {
        val s = CompositionStats().apply { start(0L) }
        repeat(100) { s.typedKey(it * 120L) } // metronome: identical gaps
        val snap = s.snapshot(finalChars = 100, nowMillis = 12_000L)
        assertEquals(Level.LOW, snap.revision)
        assertEquals(Level.LOW, snap.nonlinear)
        assertEquals(Level.LOW, snap.longPauses)
        assertEquals(Level.LOW, snap.rhythm)
    }

    @Test
    fun composingReadsAsRevisedPausedAndIrregular() {
        val s = CompositionStats().apply { start(0L) }
        var t = 0L
        val gaps = longArrayOf(80, 400, 90, 1_500, 60, 700, 3_500, 110, 950, 70)
        repeat(100) { i -> t += gaps[i % gaps.size]; s.typedKey(t) }
        s.deleted(30)
        repeat(3) { s.nonlinearEdit() }
        val snap = s.snapshot(finalChars = 80, nowMillis = t)
        assertEquals(Level.HIGH, snap.revision) // 0.375 deleted per final char
        assertEquals(Level.HIGH, snap.nonlinear) // 3.75 per 100
        assertEquals(Level.VERY_HIGH, snap.longPauses) // 10 pauses over 2 s → 12.5 per 100
        assertTrue(snap.rhythm!! >= Level.HIGH)
    }

    @Test
    fun rhythmNeedsEnoughKeys() {
        val s = CompositionStats().apply { start(0L) }
        repeat(5) { s.typedKey(it * 100L) }
        assertNull(s.snapshot(5, 1_000L).rhythm)
    }

    @Test
    fun bucketsAreUpperExclusive() {
        val t = doubleArrayOf(1.0, 2.0, 3.0)
        assertEquals(Level.LOW, CompositionStats.bucket(0.99, t))
        assertEquals(Level.MID, CompositionStats.bucket(1.0, t))
        assertEquals(Level.VERY_HIGH, CompositionStats.bucket(3.0, t))
    }
}
