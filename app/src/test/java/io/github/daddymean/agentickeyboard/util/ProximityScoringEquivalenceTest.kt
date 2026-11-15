package io.github.daddymean.agentickeyboard.util

import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks [ProximitySpelling.scored] against the plain algorithm it had before the
 * scan was made allocation-free (reused distance matrix, key geometry in flat
 * tables). The reference below is that original, kept as it was. Costs and
 * scores are compared exactly: the fast path must perform the same arithmetic in
 * the same order, so any difference means the optimisation changed behaviour.
 */
class ProximityScoringEquivalenceTest {

    private val ranked = File("src/main/res/raw/wordlist.txt").readLines()
        .map { it.trim().lowercase() }.filter { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } }.distinct()
    private val ranks = ranked.withIndex().associate { (i, word) -> word to i }
    private val engine = ProximitySpelling(ranked, ranks)

    // --- the original algorithm, kept verbatim for comparison -----------------

    private val keyPositions: Map<Char, Pair<Double, Double>> = buildMap {
        listOf("qwertyuiop" to 0.0, "asdfghjkl" to 0.5, "zxcvbnm" to 1.5).forEachIndexed { row, (keys, indent) ->
            keys.forEachIndexed { column, key -> put(key, (column + indent) to row.toDouble()) }
        }
    }
    private val vowels = setOf('a', 'e', 'i', 'o', 'u')

    private fun referenceScored(token: String, taps: List<TapOffset?>?, limit: Int): List<ProximitySpelling.Scored> {
        val maxCost = when {
            token.length <= 3 -> 1.0
            token.length == 4 -> 1.2
            token.length == 5 -> 1.5
            else -> 2.0
        }
        val usableTaps = taps?.takeIf { it.size == token.length }
        val scored = ArrayList<ProximitySpelling.Scored>()
        for (word in ranked) {
            if (abs(word.length - token.length) > 2) continue
            if (!plausibleStart(token, word)) continue
            val cost = cost(token, word, usableTaps, maxCost)
            if (cost > maxCost) continue
            var shared = 0
            while (shared < token.length && shared < word.length && token[shared] == word[shared]) shared++
            val score = cost * 4.0 + log10((ranks[word] ?: ranked.size) + 10.0) - 0.3 * minOf(shared, 4)
            scored.add(ProximitySpelling.Scored(word, cost, score))
        }
        return scored.sortedBy { it.score }.take(limit)
    }

    private fun plausibleStart(token: String, word: String): Boolean {
        val t = token[0]
        val w = word[0]
        return t == w || ProximitySpelling.areNeighbours(t, w) ||
            (token.length > 1 && word.length > 1 && token[1] == w && token[0] == word[1])
    }

    private fun cost(typed: String, word: String, taps: List<TapOffset?>?, limit: Double): Double {
        val n = typed.length
        val m = word.length
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) d[i][0] = d[i - 1][0] + deletionCost(typed, i - 1)
        for (j in 1..m) d[0][j] = d[0][j - 1] + insertionCost(word, j - 1)
        for (i in 1..n) {
            var rowMin = Double.MAX_VALUE
            for (j in 1..m) {
                var v = minOf(
                    d[i - 1][j] + deletionCost(typed, i - 1),
                    d[i][j - 1] + insertionCost(word, j - 1),
                    d[i - 1][j - 1] + substitutionCost(typed[i - 1], word[j - 1], taps?.get(i - 1))
                )
                if (i > 1 && j > 1 && typed[i - 1] == word[j - 2] && typed[i - 2] == word[j - 1]) {
                    v = minOf(v, d[i - 2][j - 2] + 0.6)
                }
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return Double.MAX_VALUE
        }
        return d[n][m]
    }

    private fun deletionCost(typed: String, index: Int): Double =
        if (index > 0 && typed[index] == typed[index - 1]) 0.5 else 1.0

    private fun insertionCost(word: String, index: Int): Double = when {
        index > 0 && word[index] == word[index - 1] -> 0.2
        word[index] in vowels -> 0.5
        else -> 1.0
    }

    private fun substitutionCost(typed: Char, intended: Char, tap: TapOffset?): Double {
        if (typed == intended) return 0.0
        val from = keyPositions[typed] ?: return 1.0
        val to = keyPositions[intended] ?: return 1.0
        val dx = to.first - from.first
        val dy = to.second - from.second
        val distance = hypot(dx, dy)
        if (distance >= 1.2) return 1.0
        val base = if (dy == 0.0 && abs(to.first - 4.5) < abs(from.first - 4.5)) 0.4 else 0.6
        if (tap == null) return base
        val lean = ((tap.dx.coerceIn(-0.75f, 0.75f) * dx + tap.dy.coerceIn(-0.75f, 0.75f) * dy) / distance) / 0.5
        return (base - 0.4 * lean).coerceIn(0.15, 1.0)
    }

    // --- inputs -----------------------------------------------------------------

    private val random = Random(20261115)
    private val letters = "abcdefghijklmnopqrstuvwxyz"

    /** One or two random edits: substitute, drop, insert or swap. */
    private fun mistype(word: String): String {
        val typed = StringBuilder(word)
        repeat(1 + random.nextInt(2)) {
            if (typed.length < 2) return@repeat
            val i = random.nextInt(typed.length)
            when (random.nextInt(4)) {
                0 -> typed.setCharAt(i, letters[random.nextInt(26)])
                1 -> typed.deleteCharAt(i)
                2 -> typed.insert(i, letters[random.nextInt(26)])
                else -> if (i + 1 < typed.length) {
                    val c = typed[i]
                    typed.setCharAt(i, typed[i + 1])
                    typed.setCharAt(i + 1, c)
                }
            }
        }
        return typed.toString()
    }

    private fun assertSame(token: String, taps: List<TapOffset?>?) {
        assertEquals("token '$token', taps=$taps", referenceScored(token, taps, 8), engine.scored(token, taps, 8))
    }

    @Test
    fun mistypedWords_scoreExactlyAsTheOriginalAlgorithm() {
        repeat(400) {
            val token = mistype(ranked[random.nextInt(ranked.size)])
            assertSame(token, null)
            assertSame(token, List(token.length) { TapOffset(random.nextFloat() - 0.5f, random.nextFloat() - 0.5f) })
        }
    }

    @Test
    fun edgeInputs_scoreExactlyAsTheOriginalAlgorithm() {
        val tokens = listOf(
            "a", "zz", "teh", "helo", "hello", "occured", "untill", "thier", "aaaaaaaaaaaaaaaaaaaaaaaa",
            "qwertyuiopasdfghjklzxcvb", "don't", "b2b", "Teh", "naïve"
        )
        for (token in tokens) {
            assertSame(token, null)
            // A trail with gaps, and one of the wrong length (which is ignored).
            assertSame(token, List(token.length) { if (it % 2 == 0) TapOffset(0.4f, -0.3f) else null })
            assertSame(token, listOf(TapOffset(0.1f, 0.1f)))
        }
    }
}
