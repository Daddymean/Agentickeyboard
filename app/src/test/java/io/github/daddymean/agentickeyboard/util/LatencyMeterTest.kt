package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.util.LatencyMeter.Metric
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyMeterTest {

    @After
    fun clear() = LatencyMeter.reset()

    private fun recordMs(metric: Metric, vararg ms: Int) = ms.forEach { LatencyMeter.record(metric, it * 1_000_000L) }

    @Test
    fun noSamples_noSummary() {
        LatencyMeter.reset()
        assertNull(LatencyMeter.summary(Metric.KEY))
    }

    @Test
    fun summary_reportsMedianP95AndMax() {
        LatencyMeter.reset()
        recordMs(Metric.SWIPE, *(1..100).toList().shuffled(java.util.Random(1)).toIntArray())
        val summary = LatencyMeter.summary(Metric.SWIPE)!!
        assertEquals(100, summary.count)
        assertEquals(50.0, summary.medianMs, 0.0)
        assertEquals(95.0, summary.p95Ms, 0.0)
        assertEquals(100.0, summary.maxMs, 0.0)
    }

    @Test
    fun metricsAreSeparate() {
        LatencyMeter.reset()
        recordMs(Metric.KEY, 2)
        assertEquals(1, LatencyMeter.summary(Metric.KEY)!!.count)
        assertNull(LatencyMeter.summary(Metric.WORD_COMMIT))
    }

    @Test
    fun onlyTheMostRecentWindowIsKept() {
        LatencyMeter.reset()
        repeat(LatencyMeter.WINDOW) { recordMs(Metric.KEY, 500) }
        repeat(LatencyMeter.WINDOW) { recordMs(Metric.KEY, 3) }
        val summary = LatencyMeter.summary(Metric.KEY)!!
        assertEquals(LatencyMeter.WINDOW, summary.count)
        assertEquals(3.0, summary.maxMs, 0.0)
    }

    @Test
    fun time_recordsTheBlockAndReturnsItsValue() {
        LatencyMeter.reset()
        assertEquals(7, LatencyMeter.time(Metric.WORD_COMMIT) { 7 })
        assertEquals(1, LatencyMeter.summary(Metric.WORD_COMMIT)!!.count)
    }

    @Test
    fun negativeDurationsAreIgnored() {
        LatencyMeter.reset()
        LatencyMeter.record(Metric.KEY, -5)
        assertNull(LatencyMeter.summary(Metric.KEY))
    }
}
