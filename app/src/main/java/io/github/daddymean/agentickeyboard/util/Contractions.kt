package io.github.daddymean.agentickeyboard.util

/**
 * English contractions typed without the apostrophe. The bundled word list
 * includes some of these stripped forms ("dont", "im", "thats"), so they must
 * not count as real words.
 */
object Contractions {
    /**
     * Stripped forms that can only mean the contraction. "yall" is left out: it is
     * in [LocalSpelling.COMMON_SLANG] and kept as typed (Keith, 2026-10-08 22:11 PT).
     */
    val UNAMBIGUOUS: Map<String, String> = mapOf(
        "dont" to "don't", "doesnt" to "doesn't", "didnt" to "didn't",
        "isnt" to "isn't", "arent" to "aren't", "wasnt" to "wasn't", "werent" to "weren't",
        "hasnt" to "hasn't", "havent" to "haven't", "hadnt" to "hadn't",
        "wouldnt" to "wouldn't", "couldnt" to "couldn't", "shouldnt" to "shouldn't",
        "mustnt" to "mustn't", "neednt" to "needn't", "cant" to "can't", "wont" to "won't",
        "aint" to "ain't", "im" to "I'm", "ive" to "I've", "youre" to "you're",
        "youve" to "you've", "youll" to "you'll", "youd" to "you'd", "theyre" to "they're",
        "theyve" to "they've", "theyll" to "they'll", "theyd" to "they'd", "weve" to "we've",
        "thats" to "that's", "whats" to "what's", "whos" to "who's", "wheres" to "where's",
        "theres" to "there's", "heres" to "here's", "hows" to "how's", "itll" to "it'll",
        "shes" to "she's", "hes" to "he's", "wouldve" to "would've", "couldve" to "could've",
        "shouldve" to "should've", "mightve" to "might've", "mustve" to "must've",
        "thatll" to "that'll", "whatre" to "what're", "whove" to "who've",
        "wholl" to "who'll"
    )

    /** Stripped forms that are also ordinary words: suggest, never auto-apply. */
    val AMBIGUOUS: Map<String, String> = mapOf(
        "ill" to "I'll", "id" to "I'd", "its" to "it's", "were" to "we're", "well" to "we'll",
        "hell" to "he'll", "shed" to "she'd", "shell" to "she'll", "wed" to "we'd", "lets" to "let's",
        "hed" to "he'd"
    )

    /** Ambiguous forms usually meant as the contraction when texting: offered first. */
    private val SUGGEST_FIRST = setOf("ill", "id", "hed")

    /**
     * The contraction to apply on space, or null. Only unambiguous forms and a
     * lone "i" are applied; case follows what was typed ("Dont" → "Don't",
     * "DONT" → "DON'T"). An apostrophe form typed with lower-case i ("i'm") gets
     * its capital.
     */
    fun autoApply(typed: String): String? {
        val lower = typed.lowercase().replace('\u2019', '\'')
        val target = when {
            lower == "i" -> "I"
            lower in I_FORMS -> "I" + lower.substring(1)
            else -> UNAMBIGUOUS[lower]
        } ?: return null
        val result = matchCase(typed, target)
        return result.takeIf { it != typed }
    }

    /** Contraction for the suggestion strip, and whether it belongs first. */
    fun suggestion(typed: String): Pair<String, Boolean>? {
        val lower = typed.lowercase()
        autoApply(typed)?.let { return it to true }
        val target = AMBIGUOUS[lower] ?: return null
        return matchCase(typed, target) to (lower in SUGGEST_FIRST)
    }

    private val I_FORMS = setOf("i'm", "i've", "i'll", "i'd")

    private fun matchCase(typed: String, target: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> target.uppercase()
        typed.firstOrNull()?.isUpperCase() == true -> target.replaceFirstChar { it.uppercase() }
        else -> target
    }
}
