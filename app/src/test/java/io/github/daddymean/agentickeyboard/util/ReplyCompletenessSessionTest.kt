package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyCompletenessSessionTest {

    private val incoming =
        "Can you bring the invoices, confirm the meeting time, and tell me whether Jim approved the quote?"

    @Test
    fun contextIsCapturedOnlyByExplicitCallAndPreviewIsBounded() {
        val session = ReplyCompletenessSession()

        assertFalse(session.state.value.hasContext)
        assertFalse(session.setIncomingContext("   "))
        assertFalse(session.state.value.hasContext)

        val longContext = "Please confirm the meeting time and send the invoice. ".repeat(200)
        assertTrue(session.setIncomingContext(longContext))
        assertTrue(session.state.value.hasContext)
        assertTrue(session.state.value.contextPreview!!.length <= ReplyCompletenessSession.MAX_PREVIEW_CHARS)
        assertTrue(session.state.value.contextWasTruncated)
    }

    @Test
    fun partialReplyPausesSendWithDerivedAdvisory() {
        val session = ReplyCompletenessSession()
        session.setIncomingContext(incoming)

        val shouldPause = session.interceptSend(
            draft = "The meeting is still at 3.",
            isSensitiveField = false
        )

        assertTrue(shouldPause)
        val warning = session.state.value.warning!!
        assertEquals(3, warning.requestCount)
        assertEquals(1, warning.answeredCount)
        assertTrue(warning.missingTopics.any { "invoice" in it })
        assertFalse(warning.toString().contains(incoming))
    }

    @Test
    fun completeReplyPassesWithoutWarning() {
        val session = ReplyCompletenessSession()
        session.setIncomingContext(incoming)

        assertFalse(
            session.interceptSend(
                draft = "I'll bring the invoices. The meeting is at 3, and Jim approved the quote.",
                isSensitiveField = false
            )
        )
        assertNull(session.state.value.warning)
    }

    @Test
    fun secondSendOnSameArmedDraftProceeds() {
        val session = ReplyCompletenessSession()
        session.setIncomingContext(incoming)
        val draft = "The meeting is still at 3."

        assertTrue(session.interceptSend(draft, isSensitiveField = false))
        assertFalse(session.interceptSend(draft, isSensitiveField = false))
        assertNull(session.state.value.warning)
    }

    @Test
    fun keepEditingRechecksWhileDismissSuppressesOnlyIdenticalDraft() {
        val session = ReplyCompletenessSession()
        session.setIncomingContext(incoming)
        val draft = "The meeting is still at 3."

        assertTrue(session.interceptSend(draft, isSensitiveField = false))
        session.keepEditing()
        assertTrue(session.interceptSend(draft, isSensitiveField = false))

        session.dismissWarning()
        assertFalse(session.interceptSend(draft, isSensitiveField = false))

        val changedDraft = "The meeting is at 3 and I will bring the invoices."
        assertTrue(session.interceptSend(changedDraft, isSensitiveField = false))
    }

    @Test
    fun sendAnywayBypassesCompletenessExactlyOnce() {
        val session = ReplyCompletenessSession()
        session.setIncomingContext(incoming)
        val draft = "The meeting is still at 3."

        assertTrue(session.interceptSend(draft, isSensitiveField = false))
        session.allowNextSend()
        assertFalse(session.interceptSend(draft, isSensitiveField = false))
        assertTrue(session.interceptSend(draft, isSensitiveField = false))
    }

    @Test
    fun draftChangesClearAnArmedWarning() {
        val session = ReplyCompletenessSession()
        session.setIncomingContext(incoming)

        assertTrue(session.interceptSend("The meeting is still at 3.", isSensitiveField = false))
        session.onDraftChanged("The meeting is at 3 and I will bring the invoices.")

        assertNull(session.state.value.warning)
        assertTrue(session.state.value.hasContext)
    }

    @Test
    fun secureFieldsBypassAndEraseContext() {
        val session = ReplyCompletenessSession()
        session.setIncomingContext(incoming)

        assertFalse(
            session.interceptSend(
                draft = "The meeting is still at 3.",
                isSensitiveField = true
            )
        )
        assertFalse(session.state.value.hasContext)
        assertNull(session.state.value.warning)
    }

    @Test
    fun clearRemovesContextWarningAndDismissalState() {
        val session = ReplyCompletenessSession()
        session.setIncomingContext(incoming)
        assertTrue(session.interceptSend("The meeting is still at 3.", isSensitiveField = false))

        session.clear()

        assertEquals(ReplyCompletenessUiState(), session.state.value)
        assertFalse(session.interceptSend("The meeting is still at 3.", isSensitiveField = false))
    }
}
