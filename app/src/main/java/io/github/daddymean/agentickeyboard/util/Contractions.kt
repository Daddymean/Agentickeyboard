package io.github.daddymean.agentickeyboard.util

/**
 * English contractions typed without the apostrophe. The bundled word list
 * includes some of these stripped forms ("dont", "im", "thats"), so they must
 * not count as real words.
 */
object Contractions {
    /** Stripped forms that can only mean the contraction. */
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
        "yall" to "y'all", "thatll" to "that'll", "whatre" to "what're", "whove" to "who've",
        "wholl" to "who'll"
    )

    /** Stripped forms that are also ordinary words: suggest, never auto-apply. */
    val AMBIGUOUS: Map<String, String> = mapOf(
        "ill" to "I'll", "id" to "I'd", "its" to "it's", "were" to "we're", "well" to "we'll",
        "hell" to "he'll", "shed" to "she'd", "shell" to "she'll", "wed" to "we'd", "lets" to "let's",
        "hed" to "he'd"
    )
}
