package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.util.IncomingSentiment.Mood
import io.github.daddymean.agentickeyboard.util.IncomingSentiment.Mood.NEGATIVE
import io.github.daddymean.agentickeyboard.util.IncomingSentiment.Mood.NEUTRAL
import io.github.daddymean.agentickeyboard.util.IncomingSentiment.Mood.POSITIVE
import io.github.daddymean.agentickeyboard.util.IncomingSentiment.Mood.TENSE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** KEYBOARD-022: accuracy and speed of the on-device mood scorer. */
class IncomingSentimentTest {

    // Labelled by hand before the scorer was tuned; everyday texts.
    private val labelled = listOf(
        "Thanks so much for helping today!" to POSITIVE,
        "I love it, you're the best" to POSITIVE,
        "Congrats on the new job!! 🎉" to POSITIVE,
        "haha that was so fun last night" to POSITIVE,
        "Sounds great, see you at 7" to POSITIVE,
        "So proud of you" to POSITIVE,
        "Happy birthday!! Hope it's amazing" to POSITIVE,
        "That's awesome news 😊" to POSITIVE,
        "Really appreciate you checking in" to POSITIVE,
        "Yay can't wait" to POSITIVE,
        "Beautiful pics ❤" to POSITIVE,
        "Perfect, thank you" to POSITIVE,
        "Ok" to NEUTRAL,
        "What time is dinner?" to NEUTRAL,
        "I'm at the store, need anything?" to NEUTRAL,
        "Call me when you get a chance" to NEUTRAL,
        "The meeting moved to 3pm" to NEUTRAL,
        "On my way" to NEUTRAL,
        "Can you send me the address" to NEUTRAL,
        "I'm home now" to NEUTRAL,
        "Did you get my email about Thursday" to NEUTRAL,
        "Pick up milk please" to NEUTRAL,
        "We're leaving in 10" to NEUTRAL,
        "Which one do you want" to NEUTRAL,
        "I'm so sad about the news" to NEGATIVE,
        "Ugh I'm exhausted and sick" to NEGATIVE,
        "Sorry, I can't make it tonight" to NEGATIVE,
        "I'm not happy with how that went" to NEGATIVE,
        "Feeling really stressed about work" to NEGATIVE,
        "My flight got cancelled 😢" to NEGATIVE,
        "That's disappointing" to NEGATIVE,
        "I'm worried about mom" to NEGATIVE,
        "It's been a rough week" to NEGATIVE,
        "I miss my dog so much, it hurts" to NEGATIVE,
        "Why didn't you call me?? Seriously" to TENSE,
        "I'm so angry right now" to TENSE,
        "This is ridiculous and unacceptable" to TENSE,
        "WHERE ARE YOU" to TENSE,
        "Are you kidding me?? Again??" to TENSE,
        "I hate when you do this 😡" to TENSE,
        "wtf is wrong with you" to TENSE,
        "Stop lying to me" to TENSE,
        "You're being so disrespectful" to TENSE,
        "I'm furious, answer your phone" to TENSE,
        "Whatever. I'm done with this, it's pathetic" to TENSE,
        "This is bullshit" to TENSE
    )

    @Test
    fun labelledSetIsMostlyRightAndNeverCallsUpsetPositive() {
        val wrong = labelled.filter { (text, mood) -> IncomingSentiment.classify(text) != mood }
            .map { (text, mood) -> "$text: want $mood, got ${IncomingSentiment.classify(text)}" }
        val right = labelled.size - wrong.size
        println("KEYBOARD-022 labelled: $right/${labelled.size} right; wrong=$wrong")
        assertTrue("accuracy $right/${labelled.size}: $wrong", right >= labelled.size * 0.85)
        val flipped = labelled.filter { (text, mood) ->
            mood in setOf(NEGATIVE, TENSE) && IncomingSentiment.classify(text) == POSITIVE
        }
        assertEquals(emptyList<Pair<String, Mood>>(), flipped)
    }

    // Written after the scorer was tuned and never tuned on: reported, with only the
    // "never calls upset or tense positive" bar enforced.
    private val heldOut = listOf(
        "Thank you!! That made my day" to POSITIVE,
        "omg I'm so excited for Saturday" to POSITIVE,
        "Love that idea 👍" to POSITIVE,
        "Nice job on the presentation" to POSITIVE,
        "Glad you made it home safe" to POSITIVE,
        "lol you're hilarious 😂" to POSITIVE,
        "Can you grab the kids at 4" to NEUTRAL,
        "Meeting is in room 204" to NEUTRAL,
        "Did you see the game" to NEUTRAL,
        "Leaving work now" to NEUTRAL,
        "How much was the ticket?" to NEUTRAL,
        "Text me the code" to NEUTRAL,
        "I failed the exam" to NEGATIVE,
        "Not feeling great today" to NEGATIVE,
        "So disappointed they cancelled" to NEGATIVE,
        "My car broke down again 😞" to NEGATIVE,
        "I'm really worried about the surgery" to NEGATIVE,
        "Sorry I forgot to call" to NEGATIVE,
        "Are you serious right now??" to TENSE,
        "Don't ever talk to me like that again" to TENSE,
        "I'm so pissed off" to TENSE,
        "This is the third time. Unbelievable." to TENSE,
        "You never listen 🙄" to TENSE,
        "STOP IGNORING MY TEXTS" to TENSE
    )

    @Test
    fun heldOutSetIsReportedAndNeverCallsUpsetPositive() {
        val wrong = heldOut.filter { (text, mood) -> IncomingSentiment.classify(text) != mood }
            .map { (text, mood) -> "$text: want $mood, got ${IncomingSentiment.classify(text)}" }
        println("KEYBOARD-022 held-out: ${heldOut.size - wrong.size}/${heldOut.size} right; wrong=$wrong")
        val flipped = heldOut.filter { (text, mood) ->
            mood in setOf(NEGATIVE, TENSE) && IncomingSentiment.classify(text) == POSITIVE
        }
        assertEquals(emptyList<Pair<String, Mood>>(), flipped)
    }

    @Test
    fun negationIntensifiersCapsAndEmojiCount() {
        assertEquals(NEGATIVE, IncomingSentiment.classify("not happy"))
        assertEquals(POSITIVE, IncomingSentiment.classify("not bad at all, thanks"))
        assertEquals(TENSE, IncomingSentiment.classify("ANSWER ME RIGHT NOW"))
        assertEquals(TENSE, IncomingSentiment.classify("ok 😡😡"))
        assertEquals(POSITIVE, IncomingSentiment.classify("HAPPY BIRTHDAY!!"))
        assertEquals(NEUTRAL, IncomingSentiment.classify("I'm home now"))
        assertEquals(NEGATIVE, IncomingSentiment.classify("Sorry, running late again"))
        assertEquals(NEUTRAL, IncomingSentiment.classify(""))
        assertEquals(NEUTRAL, IncomingSentiment.classify("   "))
    }

    @Test
    fun isFastEnoughToRunWhenTheKeyboardOpens() {
        val text = labelled.joinToString(" ") { it.first }.take(1_000)
        repeat(2_000) { IncomingSentiment.classify(text) } // warm up
        val runs = 5_000
        val start = System.nanoTime()
        repeat(runs) { IncomingSentiment.classify(text) }
        val perCallUs = (System.nanoTime() - start) / 1_000.0 / runs
        println("KEYBOARD-022 latency: %.1f µs per 1,000-char message".format(perCallUs))
        // Generous bound for slow CI machines; typical is tens of microseconds.
        assertTrue("$perCallUs µs", perCallUs < 2_000)
    }
}
