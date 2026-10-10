package io.github.daddymean.agentickeyboard.twin

/**
 * KEYBOARD-024: style statistics derived from the twin's stored messages, computed
 * on demand on the device (nothing extra is persisted). These feed the twin's later
 * persona notes (slice 3 export): which words and phrases he uses, how he
 * punctuates, which emoji he likes, how long his sentences run, and how formal
 * he writes.
 */
data class TwinStyleStats(
    val messages: Int,
    val words: Int,
    val sentences: Int,
    val avgWordsPerSentence: Double,
    val avgWordsPerMessage: Double,
    val topWords: List<Pair<String, Int>>,
    val topBigrams: List<Pair<String, Int>>,
    val topTrigrams: List<Pair<String, Int>>,
    val topEmoji: List<Pair<String, Int>>,
    /** Uses per 100 messages. */
    val punctuationPer100Messages: Map<String, Double>,
    /** Share of messages that start with a lower-case letter. */
    val lowercaseStartShare: Double,
    /** Contractions per 100 words. */
    val contractionsPer100Words: Double,
    /** Casual markers (lol, gonna, u, ...) per 100 words. */
    val casualPer100Words: Double,
    /** Formal markers (regards, therefore, ...) per 100 words. */
    val formalPer100Words: Double,
    /** 0 = very casual, 1 = very formal. */
    val formality: Double
) {
    class Builder(private val top: Int = 20) {
        private var messages = 0
        private var words = 0
        private var sentences = 0
        private var lowercaseStarts = 0
        private var contractions = 0
        private var casual = 0
        private var formal = 0
        private val wordCounts = HashMap<String, Int>()
        private val bigrams = HashMap<String, Int>()
        private val trigrams = HashMap<String, Int>()
        private val emoji = HashMap<String, Int>()
        private val punctuation = LinkedHashMap<String, Int>().apply { PUNCTUATION.forEach { put(it, 0) } }

        fun add(text: String): Builder {
            if (text.isBlank()) return this
            messages++
            val trimmed = text.trim()
            if (trimmed.first().isLowerCase()) lowercaseStarts++
            sentences += SENTENCE_END.findAll(trimmed).count().coerceAtLeast(1)
            PUNCTUATION.forEach { p -> punctuation[p] = punctuation.getValue(p) + countOf(trimmed, p) }
            EMOJI.findAll(trimmed).forEach { emoji.merge(it.value, 1, Int::plus) }
            val tokens = WORD.findAll(trimmed.replace(MARKER, " ")).map { it.value.lowercase() }.toList()
            words += tokens.size
            tokens.forEach { w ->
                wordCounts.merge(w, 1, Int::plus)
                if ('\'' in w || '\u2019' in w) contractions++
                if (w in CASUAL) casual++
                if (w in FORMAL) formal++
            }
            for (i in 0 until tokens.size - 1) bigrams.merge("${tokens[i]} ${tokens[i + 1]}", 1, Int::plus)
            for (i in 0 until tokens.size - 2) trigrams.merge("${tokens[i]} ${tokens[i + 1]} ${tokens[i + 2]}", 1, Int::plus)
            return this
        }

        fun build(): TwinStyleStats {
            val per100Words = { n: Int -> if (words == 0) 0.0 else n * 100.0 / words }
            val casualRate = per100Words(casual)
            val formalRate = per100Words(formal)
            val lowerShare = if (messages == 0) 0.0 else lowercaseStarts.toDouble() / messages
            val contractionRate = per100Words(contractions)
            // A simple, explainable score: formal markers push up; casual markers,
            // contractions, lower-case starts and "!!" push down.
            val raw = 0.5 + 0.08 * formalRate - 0.06 * casualRate - 0.02 * contractionRate - 0.2 * lowerShare
            return TwinStyleStats(
                messages = messages,
                words = words,
                sentences = sentences,
                avgWordsPerSentence = if (sentences == 0) 0.0 else words.toDouble() / sentences,
                avgWordsPerMessage = if (messages == 0) 0.0 else words.toDouble() / messages,
                topWords = topOf(wordCounts, minCount = 1),
                topBigrams = topOf(bigrams, minCount = 2),
                topTrigrams = topOf(trigrams, minCount = 2),
                topEmoji = topOf(emoji, minCount = 1),
                punctuationPer100Messages = punctuation.mapValues { (_, n) -> if (messages == 0) 0.0 else n * 100.0 / messages },
                lowercaseStartShare = lowerShare,
                contractionsPer100Words = contractionRate,
                casualPer100Words = casualRate,
                formalPer100Words = formalRate,
                formality = raw.coerceIn(0.0, 1.0)
            )
        }

        private fun topOf(counts: Map<String, Int>, minCount: Int) =
            counts.entries.filter { it.value >= minCount }
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(top).map { it.key to it.value }

        private fun countOf(text: String, token: String): Int {
            var n = 0
            var i = text.indexOf(token)
            while (i >= 0) { n++; i = text.indexOf(token, i + token.length) }
            return n
        }
    }

    companion object {
        fun of(texts: Iterable<String>): TwinStyleStats = Builder().apply { texts.forEach { add(it) } }.build()

        /** Punctuation habits tracked; "..." and "!!" are counted as their own habits. */
        val PUNCTUATION = listOf("...", "!!", "!", "?", ",", ";", ":", "-")
        private val SENTENCE_END = Regex("[.!?]+(\\s|$)|\\n+")
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['\u2019][\\p{L}]+)?")
        private val MARKER = Regex("\\[REDACTED_[A-Z_]+]")
        private val EMOJI = Regex(
            "(?:[\\x{1F1E6}-\\x{1F1FF}]{2})|(?:[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}][\\x{FE0F}\\x{1F3FB}-\\x{1F3FF}]?)"
        )
        private val CASUAL = setOf(
            "lol", "lmao", "haha", "hahaha", "omg", "gonna", "wanna", "gotta", "kinda", "sorta", "ya", "yeah", "yep",
            "nope", "u", "ur", "thx", "pls", "plz", "btw", "idk", "tbh", "imo", "hey", "dude", "bro", "yo", "k", "ok", "okay"
        )
        private val FORMAL = setOf(
            "regards", "sincerely", "therefore", "however", "furthermore", "moreover", "kindly", "please", "thank",
            "appreciate", "accordingly", "regarding", "pursuant", "dear", "respectfully", "additionally"
        )
    }
}
