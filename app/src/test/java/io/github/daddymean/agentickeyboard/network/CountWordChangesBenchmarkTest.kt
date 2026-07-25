package io.github.daddymean.agentickeyboard.network

import org.junit.Test
import kotlin.system.measureNanoTime

class CountWordChangesBenchmarkTest {

    @Test
    fun benchmarkCountWordChanges() {
        val original = "This is a test sentence with some words."
        val corrected = "This is a testing sentence with some different words."

        // Warmup
        for (i in 0..10000) {
            GeminiManager.countWordChanges(original, corrected)
        }

        // Measure
        val time = measureNanoTime {
            for (i in 0..10000) {
                GeminiManager.countWordChanges(original, corrected)
            }
        }
        println("countWordChanges benchmark took ${time / 1_000_000.0} ms for 10000 iterations.")
    }
}
