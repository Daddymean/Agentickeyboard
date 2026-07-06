package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.SwipePoint
import io.github.daddymean.agentickeyboard.util.SwipeToTypeEngine
import org.junit.Test
import kotlin.system.measureTimeMillis

class SwipeToTypeEngineBenchmark {

    @Test
    fun benchmarkGetSwipeWordMatches() {
        // Create a large fake user vocabulary
        val userVocab = (1..1000).map { "userword${it}" }

        // Setup a dummy path
        val path = listOf(
            SwipePoint(6.0f, 1.5f),
            SwipePoint(2.5f, 0.5f),
            SwipePoint(9.0f, 1.5f),
            SwipePoint(8.5f, 0.5f)
        )

        // Warm up
        for (i in 1..10) {
            SwipeToTypeEngine.getSwipeWordMatches(path, userVocab)
        }

        // Measure
        val iterations = 100
        val time = measureTimeMillis {
            for (i in 1..iterations) {
                SwipeToTypeEngine.getSwipeWordMatches(path, userVocab)
            }
        }

        println("Average time per call: ${time.toFloat() / iterations} ms")
    }
}
