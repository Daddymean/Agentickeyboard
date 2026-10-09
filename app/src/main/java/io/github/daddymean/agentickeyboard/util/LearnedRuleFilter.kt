package io.github.daddymean.agentickeyboard.util

/**
 * KEYBOARD-008: which Fix Grammar changes may become permanent spelling rules.
 *
 * A learned rule fires on every space, so it must only ever fix a misspelling,
 * never rewrite a real word. Before this, every changed word was learned with its
 * apostrophes stripped, so "your" → "you're" became the rule `your → youre` and
 * "there" → "their" became `there → their`.
 */
object LearnedRuleFilter {
    private val WHITESPACE = "\\s+".toRegex()
    private val CONTRACTION = "^[a-z]+'[a-z]+$".toRegex()

    /** Lower-cased word with curly apostrophes made straight and outer punctuation removed. */
    fun normalizeToken(token: String): String =
        token.replace('\u2019', '\'').trim { !it.isLetter() }.lowercase()

    /** A word with one inner apostrophe, such as "don't" or "you're". */
    fun isContractionLike(word: String): Boolean = CONTRACTION.matches(word)

    /**
     * Typo → fix pairs that are safe to keep. The typed word must not be a real or
     * personal word, the fix must be a real word or a contraction, and the two must
     * be within two edits. Nothing is learned until the dictionary is loaded.
     */
    fun learnablePairs(
        original: String,
        corrected: String,
        dictionaryLoaded: Boolean,
        isWord: (String) -> Boolean,
        personalWords: Set<String> = emptySet()
    ): List<Pair<String, String>> {
        if (!dictionaryLoaded) return emptyList()
        val before = original.trim().split(WHITESPACE).map(::normalizeToken)
        val after = corrected.trim().split(WHITESPACE).map(::normalizeToken)
        if (before.size != after.size) return emptyList()
        return before.indices.mapNotNull { i ->
            val typo = before[i]
            val fix = after[i]
            when {
                typo.length <= 2 || typo == fix -> null
                typo.any { it !in 'a'..'z' } -> null
                isWord(typo) || typo in personalWords -> null
                !(isWord(fix) || isContractionLike(fix)) -> null
                editDistance(typo, fix) > 2 -> null
                else -> typo to fix
            }
        }.distinct()
    }

    /** A stored rule that rewrites a real word or produces a non-word (for example `your → youre`). */
    fun isHarmful(typo: String, correction: String, isWord: (String) -> Boolean): Boolean {
        val t = normalizeToken(typo)
        val c = correction.replace('\u2019', '\'').lowercase()
        return isWord(t) || !(isWord(c) || isContractionLike(c))
    }

    /** Optimal string alignment distance (adjacent transposition counts as one edit). */
    fun editDistance(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
        return d[a.length][b.length]
    }

    /** Splits trailing sentence punctuation off a typed word: "teh," → ("teh", ","). */
    fun splitTrailingPunctuation(word: String): Pair<String, String> {
        val core = word.trimEnd { it in TRAILING_PUNCTUATION }
        return core to word.substring(core.length)
    }

    private const val TRAILING_PUNCTUATION = ".,!?;:)\"”"
}
