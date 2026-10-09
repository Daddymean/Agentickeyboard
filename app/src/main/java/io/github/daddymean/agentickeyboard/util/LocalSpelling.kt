package io.github.daddymean.agentickeyboard.util

/** Immutable frequency-ranked index. Lookups never scan the whole dictionary. */
class LocalSpelling(words: List<String>) {
    private val ranked = words.map { it.lowercase() }.filter { it.all { c -> c in 'a'..'z' } }.distinct()
    private val ranks = ranked.withIndex().associate { it.value to it.index }
    private val deletions = buildMap<String, MutableList<String>> {
        for (word in ranked) for (i in word.indices) {
            getOrPut(word.removeRange(i, i + 1)) { mutableListOf() }.add(word)
        }
    }
    fun suggestions(word: String, limit: Int = 3): List<String> {
        val token = word.lowercase()
        if (token.length !in 2..24 || token.any { it !in 'a'..'z' }) return emptyList()
        val candidates = linkedSetOf<String>()
        if (token !in ranks) {
            deletions[token]?.let { candidates.addAll(it) }
            for (i in token.indices) {
                val deleted = token.removeRange(i, i + 1)
                if (deleted in ranks) candidates.add(deleted)
                deletions[deleted]?.let { candidates.addAll(it) }
                if (i < token.lastIndex) {
                    val swapped = token.substring(0, i) + token[i + 1] + token[i] + token.substring(i + 2)
                    if (swapped in ranks) candidates.add(swapped)
                }
            }
        }
        return (candidates.filter { oneEditAway(token, it) }.sortedBy { ranks[it] ?: Int.MAX_VALUE } +
            ranked.asSequence().filter { it.startsWith(token) && it != token }.take(limit).toList())
            .distinct().take(limit)
    }
    private fun oneEditAway(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        if (a.length == b.length) {
            val different = a.indices.filter { a[it] != b[it] }
            return different.size == 1 || (different.size == 2 && different[1] == different[0] + 1 &&
                a[different[0]] == b[different[1]] && a[different[1]] == b[different[0]])
        }
        val shorter = if (a.length < b.length) a else b
        val longer = if (a.length < b.length) b else a
        val first = shorter.indices.firstOrNull { shorter[it] != longer[it] } ?: shorter.length
        return longer.removeRange(first, first + 1) == shorter
    }
    companion object {
        @Volatile var shared = LocalSpelling(listOf("the", "and", "you", "this", "keyboard", "hello"))
    }
}
