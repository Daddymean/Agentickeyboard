package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceMatchScorerTest {

    private val vocabulary = listOf(
        VoiceVocabulary("thanks", 18),
        VoiceVocabulary("quick", 14),
        VoiceVocabulary("update", 12),
        VoiceVocabulary("appreciate", 10),
        VoiceVocabulary("really", 9),
        VoiceVocabulary("great", 8),
        VoiceVocabulary("soon", 7),
        VoiceVocabulary("check", 6)
    )

    private val samples = listOf(
        VoiceSample("Thanks for the quick update! I really appreciate it.", 9),
        VoiceSample("Great, thanks! I'll check it and get back to you soon.", 11),
        VoiceSample("I really appreciate the update. I'll take a quick look!", 10),
        VoiceSample("Thanks! I'll check and let you know soon.", 8)
    )

    @Test
    fun familiarCandidateScoresHigherThanContrastingRegister() {
        val familiar = VoiceMatchScorer.score(
            "Thanks for the quick update! I'll check it and get back to you soon.",
            vocabulary,
            samples
        )
        val contrasting = VoiceMatchScorer.score(
            "Pursuant to the aforementioned correspondence, further deliberation shall commence accordingly.",
            vocabulary,
            samples
        )

        assertNotNull(familiar)
        assertNotNull(contrasting)
        assertTrue(familiar!!.percent > contrasting!!.percent)
        assertTrue(familiar.percent >= 60)
    }

    @Test
    fun insufficientEvidenceDoesNotInventAPercentage() {
        val result = VoiceMatchScorer.score(
            candidate = "A complete candidate sentence is here.",
            vocabulary = listOf(VoiceVocabulary("candidate", 1)),
            samples = listOf(VoiceSample("One tiny sample."))
        )

        assertNull(result)
    }

    @Test
    fun punctuationAndRhythmInfluenceOtherwiseSimilarText() {
        val matching = VoiceMatchScorer.score(
            "Thanks for the quick update! I'll check soon.",
            vocabulary,
            samples
        )
        val different = VoiceMatchScorer.score(
            "Thanks quick update check soon????????????????",
            vocabulary,
            samples
        )

        assertNotNull(matching)
        assertNotNull(different)
        assertTrue(matching!!.percent > different!!.percent)
    }

    @Test
    fun outputContainsOnlyDerivedSignals() {
        val privatePhrase = "Thanks for the quick update and the cobalt invoice detail."
        val result = VoiceMatchScorer.score(privatePhrase, vocabulary, samples)

        assertNotNull(result)
        assertTrue(result!!.percent in 5..98)
        assertTrue(result.confidence in 1..100)
        assertTrue(result.signals.none { privatePhrase.contains(it, ignoreCase = true) })
        assertTrue("cobalt" !in result.toString().lowercase())
    }

    @Test
    fun vocabularyOnlyProfileCanProduceAnEarlyEstimate() {
        val result = VoiceMatchScorer.score(
            "Thanks for the quick update, I really appreciate it.",
            vocabulary,
            emptyList()
        )

        assertNotNull(result)
        assertTrue(result!!.confidence < 100)
        assertTrue(result.signals.isNotEmpty())
    }
}
