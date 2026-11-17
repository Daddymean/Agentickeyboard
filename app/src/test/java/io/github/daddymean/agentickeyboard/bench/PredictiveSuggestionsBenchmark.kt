package io.github.daddymean.agentickeyboard.bench

import io.github.daddymean.agentickeyboard.util.LocalSpelling
import org.junit.Test
import java.io.File

/**
 * Per-keystroke prediction cost (LocalSpelling.predictiveSuggestions, the work
 * behind the SUGGESTIONS latency metric), on the box JVM with the real word
 * lists. Every prefix of each word is one "keystroke"; typos substitute a
 * neighbouring key. Prints p50/p95/max; asserts nothing.
 */
class PredictiveSuggestionsBenchmark {

    private fun raw(name: String): List<String> {
        val f = listOf(File("src/main/res/raw/$name.txt"), File("app/src/main/res/raw/$name.txt")).first { it.exists() }
        return f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private val neighbours = mapOf(
        'a' to 's', 'e' to 'r', 'i' to 'o', 'o' to 'p', 'n' to 'm', 't' to 'y', 's' to 'd', 'r' to 't', 'l' to 'k', 'h' to 'j'
    )

    @Test
    fun benchmarkPredictiveSuggestions() {
        val words = raw("wordlist")
        val spelling = LocalSpelling(words.take(10_000), raw("spelling_known_only"), raw("spelling_modern") + raw("spelling_extra"))
        val sample = words.filter { it.length in 4..10 && it.all { c -> c in 'a'..'z' } }.let { w -> (0 until 300).map { w[(it * 31) % w.size] } }
        val keystrokes = sample.flatMap { w ->
            val typo = w.mapIndexed { i, c -> if (i == w.length / 2) neighbours[c] ?: c else c }.joinToString("")
            (1..w.length).map { w.take(it) } + (1..typo.length).map { typo.take(it) }
        }
        fun run(proximity: Boolean): String {
            repeat(2) { keystrokes.forEach { spelling.predictiveSuggestions(it, emptyList(), null, proximity = proximity) } }
            val ns = LongArray(keystrokes.size)
            keystrokes.forEachIndexed { i, k ->
                val t = System.nanoTime()
                spelling.predictiveSuggestions(k, emptyList(), null, proximity = proximity)
                ns[i] = System.nanoTime() - t
            }
            ns.sort()
            fun ms(p: Double) = "%.3f".format(ns[((ns.size - 1) * p).toInt()] / 1e6)
            return "p50 ${ms(0.5)} ms, p95 ${ms(0.95)} ms, p99 ${ms(0.99)} ms, max ${ms(1.0)} ms"
        }
        println("PREDICT proximity=on  over ${keystrokes.size} keystrokes: ${run(true)}")
        println("PREDICT proximity=off over ${keystrokes.size} keystrokes: ${run(false)}")
    }
}
