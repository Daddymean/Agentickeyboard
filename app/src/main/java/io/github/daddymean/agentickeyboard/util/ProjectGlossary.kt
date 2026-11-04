package io.github.daddymean.agentickeyboard.util

/**
 * Keith's project nouns (Vault, Nexus, SKUs...) from a list he curates himself in
 * settings. Nothing is learned from typing: the only input is the text he enters
 * in the glossary box. Terms protect words from auto-fix and can be offered as
 * prefix suggestions.
 *
 * Entries that look like secrets or contact details (contain '@', ':' or '/', or a
 * long run of digits) are dropped, so the glossary can never become a store of
 * sensitive values. Pure Kotlin for JVM tests.
 */
object ProjectGlossary {
    const val MAX_TERMS = 200
    const val MAX_TERM_LENGTH = 40
    private val SEPARATORS = Regex("[\\n,;]+")
    private val ALLOWED = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}'._ -]*$")
    private val LONG_DIGITS = Regex("\\d{6,}")

    /** Parses the curated text into distinct terms, preserving the user's casing and order. */
    fun parse(raw: String): List<String> {
        val seen = HashSet<String>()
        return raw.split(SEPARATORS)
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { isAcceptable(it) && seen.add(it.lowercase()) }
            .take(MAX_TERMS)
    }

    fun isAcceptable(term: String): Boolean =
        term.length in 2..MAX_TERM_LENGTH &&
            ALLOWED.matches(term) &&
            !LONG_DIGITS.containsMatchIn(term) &&
            !SecretTokenPatterns.containsToken(term)

    /** Lowercased single words for the auto-fix guard (multi-word terms contribute each word). */
    fun autoFixGuard(terms: List<String>): Set<String> =
        terms.flatMap { it.lowercase().split(' ') }.filter { it.length >= 2 }.toSet()

    /** Glossary terms starting with [prefix] (case-insensitive), shortest first. */
    fun suggestions(prefix: String, terms: List<String>, limit: Int = 3): List<String> {
        if (prefix.length < 2) return emptyList()
        return terms.filter { it.startsWith(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }
            .sortedBy { it.length }
            .take(limit)
    }
}
