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

    /**
     * Runs on every keystroke of an unknown word and, through auto-fix, on the
     * main thread at each space, so the scan allocates nothing per dictionary
     * word: one distance matrix is reused, and key geometry comes from the tables
     * in the companion instead of map lookups. The arithmetic and its order are
     * those of the plain weighted Damerau-Levenshtein distance, so the costs,
     * scores and ranking are bit-for-bit what the straightforward version gives.
     */
    fun scored(token: String, taps: List<TapOffset?>?, limit: Int): List<Scored> {
        val n = token.length
        val scored = ArrayList<Scored>()
        if (n == 0) return scored
        val maxCost = maxCost(n)
        val usableTaps = taps?.takeIf { it.size == n }
        val typedKeys = IntArray(n) { keyIndex(token[it]) }
        val first = token[0]
        // Candidates are at most two letters longer than the token.
        val width = n + 3
        val d = DoubleArray((n + 1) * width)
        // Column 0 (deleting every typed letter so far) is the same for every word.
        for (i in 1..n) d[i * width] = d[(i - 1) * width] + deletionCost(token, i - 1)
        val insertion = DoubleArray(n + 2)
        for (word in ranked) {
            val m = word.length
            if (abs(m - n) > 2) continue
            // The first letter is right, a neighbour, or swapped with the second.
            val w0 = word[0]
            if (first != w0 && !NEIGHBOURS[typedKeys[0] * KEYS + keyIndex(w0)] &&
                !(n > 1 && m > 1 && token[1] == w0 && first == word[1])
            ) continue
            val cost = cost(token, typedKeys, word, usableTaps, maxCost, d, width, insertion)
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

    /**
     * Weighted Damerau-Levenshtein distance, abandoned once every path exceeds
     * [limit]. [d] is the caller's reusable matrix ([width] columns per row) with
     * column 0 already filled; [insertion] is scratch space for the word's
     * per-letter insertion costs.
     */
    private fun cost(
        typed: String,
        typedKeys: IntArray,
        word: String,
        taps: List<TapOffset?>?,
        limit: Double,
        d: DoubleArray,
        width: Int,
        insertion: DoubleArray
    ): Double {
        val n = typed.length
        val m = word.length
        for (j in 0 until m) insertion[j] = insertionCost(word, j)
        for (j in 1..m) d[j] = d[j - 1] + insertion[j - 1]
        for (i in 1..n) {
            val row = i * width
            val above = row - width
            val typedChar = typed[i - 1]
            val typedKey = typedKeys[i - 1] * KEYS
            val deletion = deletionCost(typed, i - 1)
            val tap = taps?.get(i - 1)
            var rowMin = Double.MAX_VALUE
            for (j in 1..m) {
                val wordChar = word[j - 1]
                val substitution = if (typedChar == wordChar) {
                    0.0
                } else {
                    val base = SUBSTITUTION[typedKey + keyIndex(wordChar)]
                    if (tap == null || base == 1.0) base else leanedSubstitution(typedChar, wordChar, base, tap)
                }
                var v = minOf(
                    d[above + j] + deletion,
                    d[row + j - 1] + insertion[j - 1],
                    d[above + j - 1] + substitution
                )
                if (i > 1 && j > 1 && typedChar == word[j - 2] && typed[i - 2] == wordChar) {
                    v = minOf(v, d[above - width + j - 2] + TRANSPOSITION)
                }
                d[row + j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return Double.MAX_VALUE
        }
        return d[n * width + m]
    }

    private fun deletionCost(typed: String, index: Int): Double =
        if (index > 0 && typed[index] == typed[index - 1]) DOUBLED_KEY else 1.0

    /**
     * The cost of a letter the user left out. A dropped vowel ("probly") is cheap,
     * and so is one half of a double letter ("helo" for "hello", KEYBOARD-011).
     */
    private fun insertionCost(word: String, index: Int): Double {
        val c = word[index]
        return when {
            index > 0 && c == word[index - 1] -> DROPPED_DOUBLE
            c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' -> DROPPED_VOWEL
            else -> 1.0
        }
    }

    /**
     * A neighbouring-key substitution ([base] below 1.0) when the tap position is
     * known: a tap that leaned towards the intended key makes it cheaper, one
     * that leaned away makes it dearer.
     */
    private fun leanedSubstitution(typed: Char, intended: Char, base: Double, tap: TapOffset): Double {
        val from = keyIndex(typed)
        val to = keyIndex(intended)
        val dx = KEY_COLUMN[to] - KEY_COLUMN[from]
        val dy = KEY_ROW[to] - KEY_ROW[from]
        val distance = hypot(dx, dy)
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

        // The same geometry as flat tables indexed by letter (a-z, then one slot
        // for anything else), for the per-keystroke scan in [scored].
        private const val KEYS = 27
        private fun keyIndex(c: Char): Int = if (c in 'a'..'z') c - 'a' else KEYS - 1
        private val KEY_COLUMN = DoubleArray(KEYS).also { for ((key, at) in KEY_POSITIONS) it[keyIndex(key)] = at.first }
        private val KEY_ROW = DoubleArray(KEYS).also { for ((key, at) in KEY_POSITIONS) it[keyIndex(key)] = at.second }
        private val NEIGHBOURS = BooleanArray(KEYS * KEYS).also {
            for (a in 'a'..'z') for (b in 'a'..'z') it[keyIndex(a) * KEYS + keyIndex(b)] = areNeighbours(a, b)
        }

        /** Substitution cost of two different letters when the tap position is unknown. */
        private val SUBSTITUTION = DoubleArray(KEYS * KEYS) { 1.0 }.also {
            for (typed in 'a'..'z') for (intended in 'a'..'z') {
                if (typed == intended) continue
                val from = KEY_POSITIONS.getValue(typed)
                val to = KEY_POSITIONS.getValue(intended)
                val dy = to.second - from.second
                if (hypot(to.first - from.first, dy) >= NEIGHBOUR_DISTANCE) continue
                it[keyIndex(typed) * KEYS + keyIndex(intended)] =
                    if (dy == 0.0 && abs(to.first - CENTRE_COLUMN) < abs(from.first - CENTRE_COLUMN)) NEIGHBOUR_INWARD else NEIGHBOUR
            }
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
