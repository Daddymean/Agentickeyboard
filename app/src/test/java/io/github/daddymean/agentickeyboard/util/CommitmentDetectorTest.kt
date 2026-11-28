package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentDetectorTest {

    @Test
    fun detectsActionableCommitmentsAndDueHints() {
        val deck = CommitmentDetector.detect("Sounds good! I'll send the deck by Friday.")
        assertNotNull(deck)
        assertEquals("Follow-up: Send the deck by Friday", deck!!.noteText)
        assertEquals("by Friday", deck.dueHint)
        assertTrue(deck.chipLabel.startsWith("📌 Save: "))

        val alex = CommitmentDetector.detect("Let me check with Alex tomorrow")
        assertNotNull(alex)
        assertEquals("Follow-up: Check with Alex tomorrow", alex!!.noteText)
        assertEquals("tomorrow", alex.dueHint)

        val dentist = CommitmentDetector.detect("Remind me to call the dentist at 3pm")
        assertNotNull(dentist)
        assertEquals("Follow-up: Call the dentist at 3pm", dentist!!.noteText)
        assertEquals("at 3pm", dentist.dueHint)

        val promise = CommitmentDetector.detect("I promise to follow up next week")
        assertNotNull(promise)
        assertEquals("Follow-up: Follow up next week", promise!!.noteText)
        assertEquals("next week", promise.dueHint)
    }

    @Test
    fun prefersMostRecentActionableClause() {
        val draft = "I'll send the invoice today. Also let me email Sarah the contract tomorrow."
        val hit = CommitmentDetector.detect(draft)
        assertNotNull(hit)
        assertEquals("Follow-up: Email Sarah the contract tomorrow", hit!!.noteText)
    }

    @Test
    fun ignoresQuestionsNegationsConditionalsAndCasualChatter() {
        val ignored = listOf(
            "",
            "   ",
            "I'll send",
            "I'll send the",
            "Let me check with",
            "/firm I'll send the deck by Friday",
            "Should I send the deck by Friday?",
            "I'll send the deck by Friday?",
            "I won't send the deck by Friday.",
            "I will not email Alex tomorrow.",
            "Maybe I'll send the deck later.",
            "If I'll send the invoice, let me know.",
            "I'll be there in 10 minutes!",
            "I'll see you soon!",
            "Thanks for sending the report yesterday."
        )
        for (draft in ignored) {
            assertNull("Expected null for: $draft", CommitmentDetector.detect(draft))
        }
    }

    @Test
    fun capsOversizedDraftsAndTruncatesChipLabel() {
        assertNull(CommitmentDetector.detect("I'll send " + "a".repeat(CommitmentDetector.MAX_CHARS)))
        val longClause = "I'll send the updated quarterly architecture slides and budget breakdown by Friday"
        val hit = CommitmentDetector.detect(longClause)
        assertNotNull(hit)
        assertTrue(hit!!.chipLabel.length <= 42)
        assertTrue(hit.chipLabel.endsWith("…"))
        assertTrue(hit.noteText.startsWith("Follow-up: Send the updated quarterly"))
    }

    @Test
    fun staysOffInSensitivePlaygroundOrWhenNotesUnavailable() {
        assertFalse(
            CommitmentDetector.shouldOffer(
                isSensitiveField = true,
                inPlaygroundMode = false,
                canSaveNotes = true
            )
        )
        assertFalse(
            CommitmentDetector.shouldOffer(
                isSensitiveField = false,
                inPlaygroundMode = true,
                canSaveNotes = true
            )
        )
        assertFalse(
            CommitmentDetector.shouldOffer(
                isSensitiveField = false,
                inPlaygroundMode = false,
                canSaveNotes = false
            )
        )
        assertTrue(
            CommitmentDetector.shouldOffer(
                isSensitiveField = false,
                inPlaygroundMode = false,
                canSaveNotes = true
            )
        )
    }
}
