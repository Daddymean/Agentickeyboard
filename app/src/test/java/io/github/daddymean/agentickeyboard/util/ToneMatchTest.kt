package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.util.IncomingSentiment.Mood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KEYBOARD-023: mapping, gating and the offline template tweaks. */
class ToneMatchTest {
    private fun badge(mood: Mood, intensity: Float = 0.6f) = IncomingMoodSession.Badge(mood, intensity, 60_000)

    @Test
    fun complimentaryForPositiveDiffuserForUpsetOrTense() {
        assertEquals(ToneMatch.Target.WARMER, ToneMatch.targetFor(Mood.POSITIVE))
        assertEquals(ToneMatch.Target.CALMER, ToneMatch.targetFor(Mood.NEGATIVE))
        assertEquals(ToneMatch.Target.CALMER, ToneMatch.targetFor(Mood.TENSE))
        assertNull(ToneMatch.targetFor(Mood.NEUTRAL))
    }

    @Test
    fun noRequestForNeutralOrEmptyDraft() {
        assertNull(ToneMatch.request("hi", badge(Mood.NEUTRAL)))
        assertNull(ToneMatch.request("   ", badge(Mood.TENSE)))
        val r = ToneMatch.request("fine", badge(Mood.TENSE, 0.8f))!!
        assertEquals(ToneMatch.Target.CALMER, r.target)
        assertEquals(0.8f, r.intensity)
    }

    @Test
    fun calmerTemplateSoftensWithoutChangingTheMessage() {
        val out = ToneMatch.template(ToneMatch.request(
            "WHY would you do that?? You never listen!!", badge(Mood.TENSE))!!)
        assertEquals("I hear you. Why would you do that? it feels like you don't listen.", out)
        assertFalse("!" in out)
        // Already acknowledging: no second opener.
        assertEquals("Sorry, running late.",
            ToneMatch.template(ToneMatch.request("Sorry, running late!", badge(Mood.NEGATIVE))!!))
        // "OK" stays as typed.
        assertTrue(ToneMatch.template(ToneMatch.request("OK fine", badge(Mood.TENSE))!!).contains("OK fine"))
    }

    @Test
    fun warmerTemplateAddsWarmthOnce() {
        assertEquals("sounds good! 😊", ToneMatch.template(ToneMatch.request("sounds good.", badge(Mood.POSITIVE))!!))
        assertEquals("yes!! 🎉".replace("!!", "!"),
            ToneMatch.template(ToneMatch.request("yes!! 🎉", badge(Mood.POSITIVE))!!))
        assertEquals("see you then?", ToneMatch.template(ToneMatch.request("see you then?", badge(Mood.POSITIVE))!!)
            .removeSuffix(" 😊"))
    }

    @Test
    fun intensityIsBoundedAndNeutralIsZero() {
        assertEquals(0f, IncomingSentiment.score("ok").intensity)
        for (text in listOf("I'm so angry right now", "This is ridiculous and unacceptable 😡😡", "thanks!")) {
            val i = IncomingSentiment.score(text).intensity
            assertTrue("$text → $i", i in 0.1f..1.0f)
        }
        assertTrue(IncomingSentiment.score("This is ridiculous and unacceptable 😡😡").intensity >
            IncomingSentiment.score("Stop lying to me").intensity)
    }
}
