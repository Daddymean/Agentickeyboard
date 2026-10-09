package io.github.daddymean.agentickeyboard.util

/**
 * Immutable frequency-ranked index. Lookups never scan the whole dictionary.
 *
 * KEYBOARD-020: [words] is the frequency list followed by the extra SCOWL words
 * (ranked after it). [knownOnly] words are recognised, so they are never
 * corrected, but never offered as a suggestion (vulgar and offensive words).
 */
class LocalSpelling(
    words: List<String>,
    knownOnly: Collection<String> = emptyList(),
    extraWords: List<String> = emptyList()
) {
    private val core = clean(words)
    private val ranked = (core + clean(extraWords)).distinct()
    // Extra words rank well below the frequency list, so a frequent word wins a
    // close call ("speelng" → "spelling", not "speeding").
    private val ranks = ranked.withIndex().associate { (i, word) ->
        word to if (i < core.size) i else i + EXTRA_RANK_OFFSET
    }
    private val coreSize = core.size
    private val knownOnly: Set<String> = knownOnly.map { it.lowercase() }.filterNot { it in ranks }.toSet()
    private val alphabetical = ranked.sorted()
    // The one-edit index (used where keyboard-aware correction is off) covers the
    // most frequent words only, to keep memory small with the extra words loaded.
    private val deletions = buildMap<String, MutableList<String>> {
        for (word in ranked.take(DELETION_INDEX_SIZE)) for (i in word.indices) {
            getOrPut(word.removeRange(i, i + 1)) { mutableListOf() }.add(word)
        }
    }
    /** True once the real dictionary has replaced the tiny built-in fallback. */
    val isLoaded: Boolean get() = ranked.size >= MIN_LOADED_WORDS

    /**
     * Whether [word] is a real dictionary word (case-insensitive). Contractions
     * typed without the apostrophe ("dont") do not count, even though the bundled
     * list contains some of them.
     */
    fun isKnownWord(word: String): Boolean {
        val lower = word.lowercase()
        return (lower in ranks || lower in knownOnly) && lower !in Contractions.UNAMBIGUOUS
    }

    fun suggestions(word: String, limit: Int = 3): List<String> {
        val token = word.lowercase()
        if (token.length !in 2..24 || token.any { it !in 'a'..'z' }) return emptyList()
        val prefixes = prefixCompletions(token, limit)
        // As in predictiveSuggestions, only a frequency-list completion stops the correction.
        val corePrefix = prefixes.any { (ranks[it] ?: Int.MAX_VALUE) < coreSize }
        if (token in ranks || token in knownOnly || corePrefix) return prefixes.map { preserveCase(word, it) }
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
        return (candidates.filter { oneEditAway(token, it) }.sortedBy { ranks[it] ?: Int.MAX_VALUE } + prefixes)
            .distinct().take(limit).map { preserveCase(word, it) }
    }
    private val proximity = ProximitySpelling(ranked, ranks)

    /**
     * Personal prefix completion keeps its original priority in the three-slot strip.
     *
     * KEYBOARD-019: with [proximity] on (the keyboard turns it off in sensitive
     * fields), a word that is not in the dictionary, has no completion, is not one
     * of the user's own words and is not common slang gets keyboard-aware
     * corrections ([ProximitySpelling]), using [taps] when they are known.
     */
    fun predictiveSuggestions(
        word: String,
        personal: List<String>,
        learned: String?,
        limit: Int = 3,
        proximity: Boolean = false,
        taps: List<TapOffset>? = null
    ): List<String> {
        val token = word.lowercase()
        val completions = personal.filter { it.startsWith(token, ignoreCase = true) && !it.equals(token, ignoreCase = true) }
        val dictionaryPrefixes = prefixCompletions(token, limit)
        // A completion from the extra words ("thw" → "thwart") does not stop the
        // correction ("the"); it is still offered after it.
        val corePrefixes = dictionaryPrefixes.filter { (ranks[it] ?: Int.MAX_VALUE) < coreSize }
        val needsCorrection = completions.isEmpty() && corePrefixes.isEmpty() && token !in ranks && token !in knownOnly
        val edit = when {
            !needsCorrection -> emptyList()
            !proximity -> suggestions(word, 1)
            token.length !in 2..24 || token.any { it !in 'a'..'z' } -> emptyList()
            token in COMMON_SLANG || personal.any { it.equals(token, ignoreCase = true) } -> emptyList()
            else -> this.proximity.corrections(token, taps, limit)
        }
        val candidates = (completions + listOfNotNull(learned) + edit + dictionaryPrefixes)
            .distinctBy { it.lowercase() }.map { preserveCase(word, it) }
        // KEYBOARD-010: the contraction ("dont" → "don't", "i" → "I") leads the strip.
        // Ambiguous forms that are usually real words ("its", "were") come second.
        val contraction = Contractions.suggestion(word) ?: return candidates.take(limit)
        val others = candidates.filterNot { it.equals(contraction.first, ignoreCase = true) }
        if (contraction.second) return (listOf(contraction.first) + others).take(limit)
        // With nothing else to show first, the word as typed leads, so tapping the
        // first chip never turns "were" into "we're".
        val lead = others.take(1).ifEmpty { listOf(word) }
        return (lead + contraction.first + others.drop(1)).take(limit)
    }

    private fun prefixCompletions(token: String, limit: Int): List<String> {
        if (token.length !in 2..24 || token.any { it !in 'a'..'z' }) return emptyList()
        val found = alphabetical.binarySearch(token)
        val start = if (found >= 0) found else -found - 1
        return alphabetical.asSequence().drop(start).takeWhile { it.startsWith(token) }
            .filter { it != token }.sortedBy { ranks[it] }.take(limit).toList()
    }

    private fun preserveCase(source: String, candidate: String): String = when {
        source.all { it.isUpperCase() } -> candidate.uppercase()
        source.firstOrNull()?.isUpperCase() == true && source.drop(1).all { it.isLowerCase() } ->
            candidate.replaceFirstChar { it.titlecase() }
        else -> candidate
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
        private const val MIN_LOADED_WORDS = 1_000
        private const val DELETION_INDEX_SIZE = 10_000
        private const val EXTRA_RANK_OFFSET = 50_000

        private fun clean(words: List<String>): List<String> =
            words.map { it.lowercase() }.filter { it.isNotEmpty() && it.all { c -> c in 'a'..'z' } }.distinct()

        /**
         * Common slang and texting words that are meant as typed. They are never
         * offered a correction (Keith, 2026-10-08 22:11 PT: "Just the most common slang").
         */
        val COMMON_SLANG: Set<String> = setOf(
            "lol", "lmao", "lmk", "btw", "thx", "ty", "pls", "plz", "idk", "omg", "brb", "tbh", "imo", "imho",
            "ngl", "fr", "rn", "smh", "tho", "ya", "yall", "gonna", "wanna", "gotta", "kinda", "sorta", "nah",
            "yep", "yup", "ok", "okay", "haha", "hahaha", "bday", "dm", "ur", "u", "k", "np", "gg", "irl", "fyi",
            "asap", "ttyl", "jk", "omw", "bc", "cuz"
        )

        @Volatile var shared = LocalSpelling(listOf("the", "and", "you", "this", "keyboard", "hello"))
    }
}
