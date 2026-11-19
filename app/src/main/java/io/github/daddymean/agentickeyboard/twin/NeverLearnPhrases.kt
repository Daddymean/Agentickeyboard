package io.github.daddymean.agentickeyboard.twin

import io.github.daddymean.agentickeyboard.twin.memory.MemoryIndex
import java.security.MessageDigest

/**
 * "Forget and never learn this again" for a phrase (twin-memory viewer).
 *
 * A phrase is its folded words (lower case, no accents, letters and digits only, like the
 * memory search). It matches a text when those words appear in a row, so "art" does not
 * match "start". The list stores only `n:sha256(words)` per phrase, never the phrase,
 * so the settings file can't show what Keith asked the twin to forget. Pure logic.
 */
object NeverLearnPhrases {
    const val MAX_WORDS = 8

    /** The folded words of [phrase], or empty when it has none or more than [MAX_WORDS]. */
    fun words(phrase: String): List<String> =
        MemoryIndex.tokens(phrase).takeIf { it.size in 1..MAX_WORDS } ?: emptyList()

    /** The stored form of [phrase], or null when it can't be used. */
    fun entry(phrase: String): String? = words(phrase).takeIf { it.isNotEmpty() }?.let { "${it.size}:${hash(it)}" }

    /** True when [text] contains any phrase in [entries]. */
    fun matches(text: String, entries: Set<String>): Boolean {
        if (entries.isEmpty()) return false
        val tokens = MemoryIndex.tokens(text)
        val sizes = entries.mapNotNull { it.substringBefore(':').toIntOrNull() }.filter { it in 1..MAX_WORDS }.toSet()
        for (n in sizes) {
            for (i in 0..tokens.size - n) {
                if ("$n:${hash(tokens.subList(i, i + n))}" in entries) return true
            }
        }
        return false
    }

    /** True when [text] holds the words of [phrase] in a row. */
    fun contains(text: String, phrase: String): Boolean {
        val p = words(phrase)
        if (p.isEmpty()) return false
        val t = MemoryIndex.tokens(text)
        if (t.size < p.size) return false
        for (i in 0..t.size - p.size) if (t.subList(i, i + p.size) == p) return true
        return false
    }

    private fun hash(words: List<String>): String =
        MessageDigest.getInstance("SHA-256").digest(("never-learn-v1\u0000" + words.joinToString("\u0000")).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
