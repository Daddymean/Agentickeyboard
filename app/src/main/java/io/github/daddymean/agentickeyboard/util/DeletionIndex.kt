package io.github.daddymean.agentickeyboard.util

/**
 * LAB-005: the one-edit "deletion" index of [LocalSpelling], packed into two
 * IntArrays instead of a HashMap<String, MutableList<String>>.
 *
 * The map form allocated a String, an ArrayList and a map entry for every
 * distinct single-letter deletion of the top 10,000 words (tens of thousands
 * of objects, several MB of heap in every keyboard process). Here each
 * (word, deleted position) pair is one Int, sorted by the deletion's
 * String.hashCode, and a lookup binary-searches the hash and confirms each hit
 * against the word itself, so hash collisions can never return a wrong word.
 *
 * [addTo] adds exactly the words the map's list held for that key, in the
 * same order (rank order, then position; a word appears twice when two
 * deletions give the same key, e.g. "book" -> "bok").
 */
internal class DeletionIndex(private val words: List<String>) {

    private val hashes: IntArray
    private val entries: IntArray // wordIndex * POSITIONS + deleted position

    init {
        var count = 0
        for (w in words) {
            require(w.length < POSITIONS) { "word too long for the deletion index" }
            count += w.length
        }
        // Hash in the high half, insertion order in the low half: sorting keeps
        // equal hashes in insertion order, which is the old map's list order.
        val packed = LongArray(count)
        val inserted = IntArray(count)
        var n = 0
        for ((index, word) in words.withIndex()) for (i in word.indices) {
            inserted[n] = index * POSITIONS + i
            packed[n] = (hashWithout(word, i).toLong() shl 32) or n.toLong()
            n++
        }
        packed.sort()
        hashes = IntArray(count) { (packed[it] shr 32).toInt() }
        entries = IntArray(count) { inserted[(packed[it] and 0xffffffffL).toInt()] }
    }

    val size: Int get() = entries.size

    /** Adds every word that becomes [key] when one letter is deleted, as the map did. */
    fun addTo(key: String, out: MutableCollection<String>) {
        val h = key.hashCode()
        var i = lowerBound(h)
        while (i < hashes.size && hashes[i] == h) {
            val word = words[entries[i] / POSITIONS]
            val pos = entries[i] % POSITIONS
            if (word.length == key.length + 1 &&
                word.regionMatches(0, key, 0, pos) &&
                word.regionMatches(pos + 1, key, pos, key.length - pos)
            ) out.add(word)
            i++
        }
    }

    private fun lowerBound(h: Int): Int {
        var lo = 0
        var hi = hashes.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (hashes[mid] < h) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        private const val POSITIONS = 64

        /** String.hashCode of [word] with the character at [skip] removed, without allocating it. */
        fun hashWithout(word: String, skip: Int): Int {
            var h = 0
            for (j in word.indices) if (j != skip) h = 31 * h + word[j].code
            return h
        }
    }
}
