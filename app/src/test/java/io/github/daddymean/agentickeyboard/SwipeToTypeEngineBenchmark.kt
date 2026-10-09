package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.SwipePoint
import io.github.daddymean.agentickeyboard.util.SwipeToTypeEngine
import org.junit.After
import org.junit.Test

/**
 * Timing probe for swipe decoding.
 *
 * This prints numbers for a human to read; it deliberately asserts no wall-clock
 * threshold, because shared CI runners vary far too much for that to mean
 * anything. The behavioural lock lives in `SwipeScoringEquivalenceTest`.
 *
 * The vocabulary is alphabetic and the gestures are full-length interpolated
 * paths, so the work resembles a real decode rather than the numbered synthetic
 * words this previously used — those contain digits, which have no key centre,
 * so they were skipped before scoring and made the measurement meaningless.
 */
class SwipeToTypeEngineBenchmark {

    private val syllables = listOf("ka", "re", "mi", "to", "sal", "ber", "din", "lo", "pra", "sen")

    /**
     * A pronounceable, alphabetic stand-in for a real vocabulary. Four syllable
     * positions give 10,000 distinct words of 8–12 characters, which is both the
     * real dictionary's size and inside `loadDictionary`'s 2..12 length filter.
     */
    private fun syntheticVocabulary(size: Int): List<String> =
        (0 until size).map { i ->
            val s = syllables
            s[i % 10] + s[(i / 10) % 10] + s[(i / 100) % 10] + s[(i / 1000) % 10]
        }.distinct()

    private fun pathFor(word: String): List<SwipePoint> =
        word.mapNotNull { SwipeToTypeEngine.keyCenters[it] }

    @After
    fun restoreDefaultDictionary() {
        SwipeToTypeEngine.loadDictionary(emptyList())
    }

    @Test
    fun benchmarkGetSwipeWordMatches() {
        SwipeToTypeEngine.loadDictionary(syntheticVocabulary(10_000))
        val userVocab = syntheticVocabulary(1_000)

        // Short, typical and long gestures. interpolatePath expands each of
        // these to hundreds of points, which is what the scoring loop walks.
        val gestures = listOf("the", "keyboard", "privacy", "sentence", "water")
            .map { pathFor(it) }
            .filter { it.size >= 2 }

        repeat(20) { gestures.forEach { SwipeToTypeEngine.getSwipeWordMatches(it, userVocab) } }

        val samples = mutableListOf<Long>()
        repeat(60) {
            gestures.forEach { path ->
                val started = System.nanoTime()
                SwipeToTypeEngine.getSwipeWordMatches(path, userVocab)
                samples += System.nanoTime() - started
            }
        }

        samples.sort()
        fun percentile(p: Double) = samples[((samples.size - 1) * p).toInt()] / 1_000_000.0
        println(
            "swipe decode over ${samples.size} calls: " +
                "p50 ${"%.2f".format(percentile(0.50))} ms, " +
                "p95 ${"%.2f".format(percentile(0.95))} ms, " +
                "max ${"%.2f".format(samples.last() / 1_000_000.0)} ms"
        )
        println("interpolated path lengths: " + gestures.map { SwipeToTypeEngine.interpolatePath(it).size })
    }
}
