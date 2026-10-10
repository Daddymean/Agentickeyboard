package io.github.daddymean.agentickeyboard.util

/**
 * KEYBOARD-022: the mood of an incoming message, estimated on the phone.
 *
 * A small English lexicon scorer: there is no model download and no network. It
 * handles negation ("not happy"), intensifiers ("so", "really"), ALL CAPS
 * shouting, repeated "!!" or "??", and a short list of hostile words and
 * profanity, which read as [Mood.TENSE]. When the evidence is weak it says
 * [Mood.NEUTRAL]: a wrong "upset" badge is worse than none.
 */
object IncomingSentiment {
    enum class Mood(val label: String, val emoji: String) {
        POSITIVE("Positive", "😊"),
        NEUTRAL("Neutral", "😐"),
        NEGATIVE("Upset", "😟"),
        TENSE("Tense", "⚠️")
    }

    private val POSITIVE = setOf(
        "thanks", "thank", "thx", "ty", "appreciate", "appreciated", "grateful", "love", "loved", "loving",
        "lovely", "great", "awesome", "amazing", "wonderful", "fantastic", "excellent", "perfect", "nice",
        "glad", "happy", "excited", "congrats", "congratulations", "proud", "yay", "woohoo", "beautiful",
        "brilliant", "fun", "enjoyed", "enjoy", "cool", "sweet", "best", "haha", "hahaha", "lol", "lmao",
        "good", "yes", "sure", "absolutely", "definitely", "cute", "adorable", "blessed", "delighted",
        "pleased", "incredible", "stoked", "thrilled", "helpful", "kind", "welcome", "xoxo"
    )
    private val NEGATIVE = setOf(
        "sad", "sorry", "upset", "disappointed", "disappointing", "unfortunately", "bad", "worse", "worst",
        "terrible", "awful", "horrible", "hurt", "hurts", "sick", "tired", "exhausted", "stressed", "worried",
        "worry", "anxious", "scared", "afraid", "lonely", "miserable", "depressed", "cry", "crying", "cried",
        "sucks", "ugh", "unfair", "frustrated", "frustrating", "annoyed", "annoying", "problem", "issue",
        "wrong", "broke", "broken", "lost", "late", "cancel", "cancelled", "canceled", "fail", "failed",
        "missed", "nobody", "never", "regret", "confused", "rough", "difficult", "hard", "pain", "rude"
    )
    private val HOSTILE = setOf(
        "angry", "furious", "pissed", "mad", "hate", "hated", "ridiculous", "unacceptable", "stupid", "idiot",
        "wtf", "damn", "dammit", "liar", "lied", "lying", "disgusting", "outrageous", "pathetic", "useless",
        "fuck", "fucking", "shit", "bs", "bullshit", "unbelievable", "disrespectful", "jerk", "screw"
    )
    /** Words that sound tense only with company: "seriously?? again" yes, "home now" no. */
    private val WEAK_HOSTILE = setOf(
        "seriously", "again", "enough", "whatever", "asap", "immediately", "now", "excuse", "insane", "hell"
    )
    private val NEGATORS = setOf("not", "no", "never", "dont", "don't", "isnt", "isn't", "wasnt", "wasn't",
        "aint", "ain't", "cant", "can't", "didnt", "didn't", "doesnt", "doesn't", "wont", "won't", "hardly")
    private val INTENSIFIERS = setOf("so", "very", "really", "super", "totally", "extremely", "absolutely", "too")
    private val TOKEN = Regex("[A-Za-z']+|[!?]+|[\\p{So}\\p{Sk}]")
    private val POSITIVE_EMOJI = setOf("😊", "😀", "😃", "😄", "😁", "😍", "🥰", "😘", "❤", "♥", "👍", "🎉", "🙏", "😂", "🤣", "💕", "🥳")
    private val NEGATIVE_EMOJI = setOf("😢", "😭", "😞", "😔", "💔", "😟", "😕", "🙁", "☹")
    private val ANGRY_EMOJI = setOf("😡", "😠", "🤬", "😤", "🙄", "🖕")

    fun classify(message: String): Mood {
        val text = message.trim()
        if (text.isEmpty()) return Mood.NEUTRAL
        val tokens = TOKEN.findAll(text).map { it.value }.toList()
        var positive = 0.0
        var negative = 0.0
        var hostile = 0.0
        var negateWindow = 0
        var boost = 1.0
        for (raw in tokens) {
            val word = raw.lowercase()
            when {
                raw.all { it == '!' } -> { if (raw.length >= 2) hostile += 0.4; continue }
                raw.all { it == '?' } -> { if (raw.length >= 2) hostile += 0.5; continue }
                raw in POSITIVE_EMOJI -> { positive += 1.0; continue }
                raw in NEGATIVE_EMOJI -> { negative += 1.0; continue }
                raw in ANGRY_EMOJI -> { hostile += 1.5; continue }
            }
            if (word in NEGATORS) { negateWindow = 3; continue }
            if (word in INTENSIFIERS) { boost = 1.5; continue }
            val shout = raw.length >= 3 && raw.all { it.isUpperCase() }
            val weight = boost * (if (shout) 1.5 else 1.0)
            val negated = negateWindow > 0
            when (word) {
                in HOSTILE -> if (!negated) hostile += weight
                in WEAK_HOSTILE -> if (!negated) hostile += weight * 0.5
                in POSITIVE -> if (negated) negative += weight else positive += weight
                in NEGATIVE -> if (negated) positive += weight * 0.3 else negative += weight
            }
            if (negateWindow > 0) negateWindow--
            boost = 1.0
        }
        val letters = text.filter { it.isLetter() }
        // Shouting a whole message reads as tense, unless it is shouted joy ("HAPPY BIRTHDAY").
        if (positive == 0.0 && letters.length >= 8 &&
            letters.count { it.isUpperCase() } >= letters.length * 0.7) hostile += 1.5
        // One strong hostile word is enough; weak ones ("again", "now") need company.
        return when {
            hostile >= 1.0 && hostile > positive -> Mood.TENSE
            negative >= 1.0 && negative > positive -> Mood.NEGATIVE
            positive >= 1.0 && positive > negative + hostile -> Mood.POSITIVE
            else -> Mood.NEUTRAL
        }
    }
}
