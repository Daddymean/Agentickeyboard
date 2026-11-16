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

    /** The dictionary as a letter tree, so words that start alike share their work. */
    private val trie = Trie(ranked)

    /**
     * Runs on every keystroke of an unknown word and, through auto-fix, at each
     * space, so it does not score the dictionary word by word. It walks the
     * letter tree instead: one row of the weighted Damerau-Levenshtein matrix
     * per letter, shared by every word below that letter, and a branch is
     * dropped as soon as its row shows that nothing beneath it can come in under
     * the cost limit. Each cell is the same minimum over the same sums as in the
     * plain word-by-word distance, so the costs, scores and ranking are exactly
     * what that gives (see ProximityScoringEquivalenceTest).
     */
    fun scored(token: String, taps: List<TapOffset?>?, limit: Int): List<Scored> {
        val n = token.length
        if (n == 0) return emptyList()
        val search = Search(token, taps?.takeIf { it.size == n }, maxCost(n))
        val first = token[0]
        val firstKey = keyIndex(first) * KEYS
        var node = trie.firstChild[0]
        while (node != NONE) {
            val letter = trie.letter[node]
            // The first letter is right, a neighbour, or swapped with the second.
            if (letter == first || NEIGHBOURS[firstKey + keyIndex(letter)]) {
                search.visit(node, 1, swappedStart = false)
            } else if (n > 1 && letter == token[1]) {
                search.visit(node, 1, swappedStart = true)
            }
            node = trie.nextSibling[node]
        }
        // Ties keep dictionary order, as a stable sort of the word-by-word scan did.
        search.found.sortWith(compareBy<Found> { it.scored.score }.thenBy { it.index })
        return search.found.take(limit).map { it.scored }
    }

    private class Found(val index: Int, val scored: Scored)

    /** One lookup: the typed token and the matrix rows for the branch being walked. */
    private inner class Search(val token: String, val taps: List<TapOffset?>?, val maxCost: Double) {
        private val n = token.length
        private val typedKeys = IntArray(n) { keyIndex(token[it]) * KEYS }
        private val deletion = DoubleArray(n) { deletionCost(token, it) }
        private val width = n + 1
        // Row r holds the cost of turning each prefix of the token into the first
        // r letters of the branch. Candidates are at most two letters longer.
        private val rows = DoubleArray((n + 3) * width).also {
            for (i in 1..n) it[i] = it[i - 1] + deletion[i - 1]
        }
        private val branch = CharArray(n + 3)
        val found = ArrayList<Found>()

        fun visit(node: Int, depth: Int, swappedStart: Boolean) {
            val letter = trie.letter[node]
            branch[depth - 1] = letter
            val previousLetter = if (depth > 1) branch[depth - 2] else NO_LETTER
            // The cost of a letter the user left out. A dropped vowel ("probly") is
            // cheap, and so is one half of a double letter ("helo", KEYBOARD-011).
            val insertion = when {
                depth > 1 && letter == previousLetter -> DROPPED_DOUBLE
                letter == 'a' || letter == 'e' || letter == 'i' || letter == 'o' || letter == 'u' -> DROPPED_VOWEL
                else -> 1.0
            }
            val letterKey = keyIndex(letter)
            val row = depth * width
            val above = row - width
            var rowMin = rows[above] + insertion
            rows[row] = rowMin
            for (i in 1..n) {
                val typedChar = token[i - 1]
                val substitution = if (typedChar == letter) {
                    0.0
                } else {
                    val base = SUBSTITUTION[typedKeys[i - 1] + letterKey]
                    val tap = taps?.get(i - 1)
                    if (tap == null || base == 1.0) base else leanedSubstitution(typedChar, letter, base, tap)
                }
                var v = minOf(
                    rows[row + i - 1] + deletion[i - 1],
                    rows[above + i] + insertion,
                    rows[above + i - 1] + substitution
                )
                if (i > 1 && depth > 1 && typedChar == previousLetter && token[i - 2] == letter) {
                    v = minOf(v, rows[above - width + i - 2] + TRANSPOSITION)
                }
                rows[row + i] = v
                if (v < rowMin) rowMin = v
            }
            // Every way of finishing a word below this letter passes through this
            // row and only adds cost, so once the whole row is over the limit the
            // branch holds no candidates.
            if (rowMin > maxCost) return

            val word = trie.word[node]
            if (word != NONE && depth >= n - 2 && !(swappedStart && depth == 1)) {
                val cost = rows[row + n]
                if (cost <= maxCost) {
                    val text = ranked[word]
                    val score = cost * COST_WEIGHT + log10((ranks[text] ?: ranked.size) + 10.0) -
                        PREFIX_BONUS * minOf(sharedPrefix(token, text), 4)
                    found.add(Found(word, Scored(text, cost, score)))
                }
            }
            if (depth == n + 2) return
            var child = trie.firstChild[node]
            while (child != NONE) {
                // A swapped start ("ht" for "th") needs the token's first letter second.
                if (!(swappedStart && depth == 1) || trie.letter[child] == token[0]) {
                    visit(child, depth + 1, swappedStart)
                }
                child = trie.nextSibling[child]
            }
        }
    }

    /**
     * The words as a letter tree in flat arrays: node 0 is the root, [word] is the
     * word's position in the list where a word ends at that node. Built once per
     * dictionary.
     */
    private class Trie(words: List<String>) {
        val letter: CharArray
        val firstChild: IntArray
        val nextSibling: IntArray
        val word: IntArray

        init {
            val capacity = words.sumOf { it.length } + 1
            val letters = CharArray(capacity)
            val children = IntArray(capacity) { NONE }
            val siblings = IntArray(capacity) { NONE }
            val ends = IntArray(capacity) { NONE }
            val lastChild = IntArray(capacity) { NONE }
            var size = 1
            for ((index, text) in words.withIndex()) {
                if (text.isEmpty()) continue
                var node = 0
                for (c in text) {
                    var child = children[node]
                    while (child != NONE && letters[child] != c) child = siblings[child]
                    if (child == NONE) {
                        child = size++
                        letters[child] = c
                        if (lastChild[node] == NONE) children[node] = child else siblings[lastChild[node]] = child
                        lastChild[node] = child
                    }
                    node = child
                }
                if (ends[node] == NONE) ends[node] = index
            }
            letter = letters.copyOf(size)
            firstChild = children.copyOf(size)
            nextSibling = siblings.copyOf(size)
            word = ends.copyOf(size)
        }
    }

    private fun maxCost(length: Int): Double = when {
        length <= 3 -> 1.0
        length == 4 -> 1.2
        length == 5 -> 1.5
        else -> 2.0
    }

    private fun deletionCost(typed: String, index: Int): Double =
        if (index > 0 && typed[index] == typed[index - 1]) DOUBLED_KEY else 1.0

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
        private const val NONE = -1
        private const val NO_LETTER = '\u0000'
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
