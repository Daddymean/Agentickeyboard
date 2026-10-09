package io.github.daddymean.agentickeyboard.util

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log10

/**
 * KEYBOARD-019: spelling correction that knows the keyboard.
 *
 * Mistyped letters are usually the key next door, and where the finger landed
 * says which neighbour was meant. Candidates are scored by a weighted edit
 * distance in which:
 * - a substitution by a neighbouring key costs less than one by a distant key;
 * - it costs less again when the intended key is the one nearer the middle of
 *   the keyboard, because two thumbs tend to land outward (Keith's sample: 12 of
 *   13 substitutions);
 * - when the tap position is known, a tap that landed on the edge facing the
 *   intended key makes that substitution cheap, and one on the far edge makes it
 *   dear;
 * - a dropped vowel costs less than a dropped consonant, and a doubled key costs
 *   half.
 *
 * Nothing here is stored. Tap offsets are held in memory for the word being typed
 * and are never collected in sensitive fields or while learning is paused (see
 * [TapTrail]).
 */
data class TapOffset(
    /** Horizontal offset from the key centre, in key widths (-0.5 = left edge). */
    val dx: Float,
    /** Vertical offset from the key centre, in key heights (-0.5 = top edge). */
    val dy: Float
)

internal class ProximitySpelling(private val ranked: List<String>, private val ranks: Map<String, Int>) {

    fun corrections(token: String, taps: List<TapOffset?>?, limit: Int): List<String> =
        scored(token, taps, limit).map { it.word }

    /** A candidate with its edit [cost] and ranking [score] (lower is better). */
    data class Scored(val word: String, val cost: Double, val score: Double)

    fun scored(token: String, taps: List<TapOffset?>?, limit: Int): List<Scored> {
        val maxCost = maxCost(token.length)
        val usableTaps = taps?.takeIf { it.size == token.length }
        val scored = ArrayList<Scored>()
        for (word in ranked) {
            if (abs(word.length - token.length) > 2) continue
            if (!plausibleStart(token, word)) continue
            val cost = cost(token, word, usableTaps, maxCost)
            if (cost > maxCost) continue
            val score = cost * COST_WEIGHT + log10((ranks[word] ?: ranked.size) + 10.0) -
                PREFIX_BONUS * minOf(sharedPrefix(token, word), 4)
            scored.add(Scored(word, cost, score))
        }
        return scored.sortedBy { it.score }.take(limit)
    }

    private fun maxCost(length: Int): Double = when {
        length <= 3 -> 1.0
        length == 4 -> 1.2
        length == 5 -> 1.5
        else -> 2.0
    }

    /** The first letter is right, a neighbour, or swapped with the second. */
    private fun plausibleStart(token: String, word: String): Boolean {
        val t = token[0]
        val w = word[0]
        return t == w || areNeighbours(t, w) ||
            (token.length > 1 && word.length > 1 && token[1] == w && token[0] == word[1])
    }

    /** Weighted Damerau-Levenshtein distance, abandoned once every path exceeds [limit]. */
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
                    v = minOf(v, d[i - 2][j - 2] + TRANSPOSITION)
                }
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return Double.MAX_VALUE
        }
        return d[n][m]
    }

    private fun deletionCost(typed: String, index: Int): Double =
        if (index > 0 && typed[index] == typed[index - 1]) DOUBLED_KEY else 1.0

    /**
     * The cost of a letter the user left out. A dropped vowel ("probly") is cheap,
     * and so is one half of a double letter ("helo" for "hello", KEYBOARD-011).
     */
    private fun insertionCost(word: String, index: Int): Double = when {
        index > 0 && word[index] == word[index - 1] -> DROPPED_DOUBLE
        word[index] in VOWELS -> DROPPED_VOWEL
        else -> 1.0
    }

    private fun substitutionCost(typed: Char, intended: Char, tap: TapOffset?): Double {
        if (typed == intended) return 0.0
        val from = KEY_POSITIONS[typed] ?: return 1.0
        val to = KEY_POSITIONS[intended] ?: return 1.0
        val dx = to.first - from.first
        val dy = to.second - from.second
        val distance = hypot(dx, dy)
        if (distance >= NEIGHBOUR_DISTANCE) return 1.0
        val base = if (dy == 0.0 && abs(to.first - CENTRE_COLUMN) < abs(from.first - CENTRE_COLUMN)) {
            NEIGHBOUR_INWARD
        } else {
            NEIGHBOUR
        }
        if (tap == null) return base
        // How far the tap leaned towards the intended key: +1 at the facing edge, -1 at the far edge.
        val lean = ((tap.dx.coerceIn(-MAX_LEAN, MAX_LEAN) * dx + tap.dy.coerceIn(-MAX_LEAN, MAX_LEAN) * dy) / distance) / 0.5
        return (base - TAP_WEIGHT * lean).coerceIn(MIN_NEIGHBOUR, 1.0)
    }

    private fun sharedPrefix(a: String, b: String): Int {
        var k = 0
        while (k < a.length && k < b.length && a[k] == b[k]) k++
        return k
    }

    companion object {
        private const val COST_WEIGHT = 4.0
        private const val PREFIX_BONUS = 0.3
        private const val NEIGHBOUR = 0.6
        private const val NEIGHBOUR_INWARD = 0.4
        private const val MIN_NEIGHBOUR = 0.15
        private const val TAP_WEIGHT = 0.4
        private const val MAX_LEAN = 0.75f
        private const val TRANSPOSITION = 0.6
        private const val DOUBLED_KEY = 0.5
        private const val DROPPED_DOUBLE = 0.2
        private const val DROPPED_VOWEL = 0.5
        private const val NEIGHBOUR_DISTANCE = 1.2
        private const val CENTRE_COLUMN = 4.5
        private val VOWELS = setOf('a', 'e', 'i', 'o', 'u')

        /** Key centres in key units: column (with the row stagger) and row. */
        private val KEY_POSITIONS: Map<Char, Pair<Double, Double>> = buildMap {
            listOf("qwertyuiop" to 0.0, "asdfghjkl" to 0.5, "zxcvbnm" to 1.5).forEachIndexed { row, (keys, indent) ->
                keys.forEachIndexed { column, key -> put(key, (column + indent) to row.toDouble()) }
            }
        }

        fun areNeighbours(a: Char, b: Char): Boolean {
            val p = KEY_POSITIONS[a] ?: return false
            val q = KEY_POSITIONS[b] ?: return false
            return a != b && hypot(p.first - q.first, p.second - q.second) < NEIGHBOUR_DISTANCE
        }
    }
}

/**
 * Where each letter of the word being typed was tapped, in memory only.
 * The keyboard records a tap only outside sensitive fields and while learning is
 * not paused; the trail is never written anywhere and holds at most one word's
 * worth of recent letters.
 */
object TapTrail {
    private const val MAX_LETTERS = 32
    private val letters = StringBuilder()
    private val offsets = ArrayList<TapOffset>()

    @Synchronized
    fun record(letter: Char, offset: TapOffset) {
        letters.append(letter.lowercaseChar())
        offsets.add(offset)
        if (letters.length > MAX_LETTERS) {
            letters.deleteCharAt(0)
            offsets.removeAt(0)
        }
    }

    @Synchronized
    fun clear() {
        letters.setLength(0)
        offsets.clear()
    }

    /** The taps behind [word], if its letters are exactly the most recent taps; otherwise null. */
    @Synchronized
    fun offsetsFor(word: String): List<TapOffset>? {
        val lower = word.lowercase()
        if (lower.isEmpty() || !letters.endsWith(lower)) return null
        return offsets.subList(offsets.size - lower.length, offsets.size).toList()
    }
}
