package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** LAB-005: the packed index returns exactly what the old String -> List map did. */
class DeletionIndexEquivalenceTest {

    private fun raw(name: String): List<String> {
        val f = listOf(File("src/main/res/raw/$name.txt"), File("app/src/main/res/raw/$name.txt")).first { it.exists() }
        return f.readLines().map { it.trim().lowercase() }.filter { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } }.distinct()
    }

    private fun reference(words: List<String>) = buildMap<String, MutableList<String>> {
        for (word in words) for (i in word.indices) getOrPut(word.removeRange(i, i + 1)) { mutableListOf() }.add(word)
    }

    private fun lookup(index: DeletionIndex, key: String) = mutableListOf<String>().also { index.addTo(key, it) }

    @Test
    fun hashWithout_matchesStringHashCode() {
        for (w in listOf("a", "ab", "book", "keyboard", "zzzzzz")) for (i in w.indices) {
            assertEquals(w.removeRange(i, i + 1).hashCode(), DeletionIndex.hashWithout(w, i))
        }
    }

    @Test
    fun everyKey_matchesTheMap_onTheRealDictionary() {
        val words = (raw("wordlist").take(10_000) + raw("spelling_modern") + raw("spelling_extra")).distinct().take(10_000)
        val ref = reference(words)
        val index = DeletionIndex(words)
        assertEquals(ref.values.sumOf { it.size }, index.size)
        for ((key, list) in ref) assertEquals(key, list, lookup(index, key))
    }

    @Test
    fun missesAndCollisions_returnNothingWrong() {
        val words = (raw("wordlist").take(10_000))
        val ref = reference(words)
        val index = DeletionIndex(words)
        // Typo-like probes: every one- and two-letter deletion and swap of a sample of words.
        val probes = words.take(2_000).flatMap { w ->
            w.indices.map { w.removeRange(it, it + 1) } + listOf(w, w + "x", "q" + w, w.reversed())
        }
        for (p in probes) assertEquals(p, ref[p].orEmpty(), lookup(index, p))
        // "Aa" and "BB" share String.hashCode; only real deletions may come back.
        val tinyWords = listOf("aab", "bbb", "aabb", "bbaa")
        val tiny = DeletionIndex(tinyWords)
        val tinyRef = reference(tinyWords)
        for (key in listOf("ab", "bb", "aab", "abb", "bba", "Aa", "BB", "AaBB")) {
            assertEquals(key, tinyRef[key].orEmpty(), lookup(tiny, key))
        }
        assertEquals(listOf("aab", "aab"), lookup(tiny, "ab"))
    }
}
