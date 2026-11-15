package io.github.daddymean.agentickeyboard.bench

import io.github.daddymean.agentickeyboard.BuildConfig
import io.github.daddymean.agentickeyboard.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchStatsTest {
    @Test
    fun benchmarkFlagIsOffByDefault() {
        // CI and plain `./gradlew assembleDebug` do not pass -PmodelBenchmark=true.
        assertFalse(BuildConfig.MODEL_BENCHMARK)
    }

    @Test
    fun medianHandlesOddEvenAndEmpty() {
        assertEquals(2.0, BenchStats.median(listOf(3.0, 1.0, 2.0))!!, 0.0)
        assertEquals(2.5, BenchStats.median(listOf(4.0, 1.0, 2.0, 3.0))!!, 0.0)
        assertNull(BenchStats.median(emptyList()))
    }

    @Test
    fun summaryIgnoresFailedRuns() {
        val ok = PromptResult("a", 100.0, 200.0, 30.0, 20, 900.0, 50.0, "x")
        val ok2 = PromptResult("b", 300.0, 100.0, 10.0, 20, 1100.0, 70.0, "y")
        val bad = PromptResult("c", null, null, null, null, 5.0, 0.0, null, "boom")
        val s = BenchStats.summarize(listOf(ok, ok2, bad))
        assertEquals(3, s.total)
        assertEquals(2, s.ok)
        assertEquals(200.0, s.medianTtftMs!!, 0.0)
        assertEquals(20.0, s.medianDecodeTps!!, 0.0)
    }

    @Test
    fun parsesVmHwm() {
        val status = "Name:\tapp\nVmPeak:\t 9000 kB\nVmHWM:\t  123456 kB\nVmRSS:\t 1000 kB\n"
        assertEquals(123456L, BenchStats.parseVmHwmKb(status))
        assertNull(BenchStats.parseVmHwmKb("Name:\tapp\n"))
    }

    @Test
    fun stripsThinkingLeaks() {
        assertEquals("Answer.", BenchStats.stripThinking("Draft.</think>Answer."))
        assertEquals("Answer.", BenchStats.stripThinking("<think>plan</think>\nAnswer."))
        assertEquals("Plain.", BenchStats.stripThinking("Plain."))
    }

    @Test
    fun factsCheckIsCaseInsensitive() {
        assertTrue(BenchStats.factsKept("See you THURSDAY at 6:00", listOf("Thursday", "6:00")))
        assertFalse(BenchStats.factsKept("See you soon", listOf("Thursday")))
        assertFalse(BenchStats.factsKept(null, listOf("x")))
    }

    @Test
    fun casesAreTwentyAndUseTheAppRewritePrompt() {
        assertEquals(20, BakeoffCases.CASES.size)
        assertEquals(20, BakeoffCases.CASES.map { it.id }.toSet().size)
        val p = BakeoffCases.prompt(BakeoffCases.CASES.first())
        assertTrue(p.startsWith("Rewrite the following English text so it is noticeably shorter"))
        assertTrue(p.endsWith(BakeoffCases.CASES.first().text))
    }

    @Test
    fun benchModelsMatchShippedPinsAndOnlyLfm3DisablesThinking() {
        assertEquals(ModelCatalog.GEMMA_4_E2B.sha256, BakeoffModels.GEMMA_4_E2B.sha256)
        assertEquals(ModelCatalog.EMBEDDING_GEMMA_2.sha256, BakeoffModels.EMBEDDING_270M.sha256)
        assertEquals(listOf("lfm3-int4", "lfm3-int8"), BakeoffModels.ALL.filter { it.disableThinking }.map { it.key })
        assertTrue(BakeoffModels.ALL.all { it.sha256.matches(Regex("[0-9a-f]{64}")) })
    }

    @Test
    fun markdownRowSaysNotMeasuredForMissingValues() {
        val s = BenchSummary(1, 0, null, null, null, null, null)
        val row = BenchStats.markdownRow("M", "CPU", s, null, 1_440_000_000L)
        assertTrue(row.contains("not measured"))
        assertTrue(row.contains("1.44 GB"))
    }
}
