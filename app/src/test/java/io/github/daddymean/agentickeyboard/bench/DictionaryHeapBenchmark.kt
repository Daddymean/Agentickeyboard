package io.github.daddymean.agentickeyboard.bench

import io.github.daddymean.agentickeyboard.util.LocalSpelling
import io.github.daddymean.agentickeyboard.util.SwipeToTypeEngine
import org.junit.After
import org.junit.Test
import java.io.File

/**
 * LAB-005 probe: retained JVM heap of the dictionaries the Application builds at
 * startup, from the real word lists. Box JVM (64-bit, compressed oops); ART's
 * object layout differs, so read it as relative. Prints for a human, asserts nothing.
 */
class DictionaryHeapBenchmark {

    private fun raw(name: String): List<String> {
        val f = listOf(File("src/main/res/raw/$name.txt"), File("app/src/main/res/raw/$name.txt")).first { it.exists() }
        return f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun used(): Long {
        val rt = Runtime.getRuntime()
        repeat(6) { System.gc(); Thread.sleep(40) }
        return rt.totalMemory() - rt.freeMemory()
    }

    @After
    fun reset() = SwipeToTypeEngine.loadDictionary(emptyList())

    @Test
    fun benchmarkDictionaryHeap() {
        val words = raw("wordlist")
        val extra = raw("spelling_modern") + raw("spelling_extra")
        val knownOnly = raw("spelling_known_only")
        // Warm up class loading so it is not counted.
        LocalSpelling(words.take(100), knownOnly, extra.take(100))
        SwipeToTypeEngine.loadDictionary(words.take(100))

        val spellingSamples = mutableListOf<Long>()
        val swipeSamples = mutableListOf<Long>()
        val buildMs = mutableListOf<Double>()
        repeat(5) {
            val base = used()
            val t0 = System.nanoTime()
            var spelling: LocalSpelling? = LocalSpelling(words.take(10_000), knownOnly, extra)
            buildMs += (System.nanoTime() - t0) / 1e6
            spellingSamples += used() - base
            check(spelling!!.isLoaded)
            spelling = null
            val base2 = used()
            SwipeToTypeEngine.loadDictionary(words)
            swipeSamples += used() - base2
            SwipeToTypeEngine.loadDictionary(emptyList())
        }
        // Typo lookups through the deletion index (suggestions() on non-words).
        val spelling = LocalSpelling(words.take(10_000), knownOnly, extra)
        val typos = words.take(3_000).filter { it.length >= 4 }.map { it.substring(0, 1) + it.substring(2) + "q" }
        repeat(3) { typos.forEach { spelling.suggestions(it) } }
        val lookupNs = (0 until 5).map {
            val t = System.nanoTime(); typos.forEach { spelling.suggestions(it) }; (System.nanoTime() - t) / typos.size
        }.sorted()[2]
        fun median(xs: List<Long>) = xs.sorted()[xs.size / 2] / 1e6
        println(
            "LAB-005 heap (box JVM): LocalSpelling ${"%.2f".format(median(spellingSamples))} MB " +
                "(build median ${"%.0f".format(buildMs.sorted()[2])} ms), " +
                "SwipeToTypeEngine dictionary ${"%.2f".format(median(swipeSamples))} MB; " +
                "typo suggestions() median ${"%.1f".format(lookupNs / 1000.0)} µs over ${typos.size} typos; " +
                "words=${words.size} extra=${extra.size}"
        )
    }
}
