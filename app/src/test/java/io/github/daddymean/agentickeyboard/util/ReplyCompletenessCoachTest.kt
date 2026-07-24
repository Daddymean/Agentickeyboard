package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyCompletenessCoachTest {

    @Test
    fun detectsOneMissingAnswerAcrossSeveralQuestions() {
        val result = ReplyCompletenessCoach.assess(
            incomingMessage = "What time is the meeting? Where should I park? Do I need to bring ID?",
            draft = "The meeting is at 3, and park in the south lot."
        )

        assertNotNull(result)
        assertEquals(3, result!!.requestCount)
        assertEquals(2, result.answeredCount)
        assertTrue(result.shouldWarn)
        assertTrue(result.missingTopics.any { "id" in it })
        assertTrue(result.confidence >= 60)
    }

    @Test
    fun splitsACompoundPoliteRequestIntoSeparateObligations() {
        val result = ReplyCompletenessCoach.assess(
            incomingMessage = "Can you bring the invoices, confirm the meeting time, and tell me whether Jim approved the quote?",
            draft = "The meeting is still at 3."
        )

        assertNotNull(result)
        assertEquals(3, result!!.requestCount)
        assertEquals(1, result.answeredCount)
        assertTrue(result.missingTopics.any { "invoice" in it })
        assertTrue(result.missingTopics.any { "jim" in it && "quote" in it })
    }

    @Test
    fun completeReplyProducesNoWarning() {
        val result = ReplyCompletenessCoach.assess(
            incomingMessage = "Can you bring the invoices, confirm the meeting time, and tell me whether Jim approved the quote?",
            draft = "I'll bring the invoices. The meeting is at 3, and Jim approved the quote."
        )

        assertNotNull(result)
        assertEquals(3, result!!.requestCount)
        assertEquals(3, result.answeredCount)
        assertFalse(result.shouldWarn)
        assertTrue(result.missingTopics.isEmpty())
        assertNull(result.advisory)
    }

    @Test
    fun singleQuestionDoesNotCreateCompletenessJudgment() {
        assertNull(
            ReplyCompletenessCoach.assess(
                incomingMessage = "Can you resend the invoice?",
                draft = "Yes, I will resend it now."
            )
        )
    }

    @Test
    fun rhetoricalQuotedAndCasualQuestionsAreSuppressed() {
        assertNull(
            ReplyCompletenessCoach.assess(
                incomingMessage = "He asked, \"Why would anyone do that?\" Who knows?",
                draft = "I agree with the rest of the message."
            )
        )
        assertNull(
            ReplyCompletenessCoach.assess(
                incomingMessage = "How are you? What's up?",
                draft = "Doing well, thanks for asking."
            )
        )
    }

    @Test
    fun shortAmbiguousAcknowledgementIsNotSecondGuessed() {
        assertNull(
            ReplyCompletenessCoach.assess(
                incomingMessage = "Did Alex approve it? Can we send it today?",
                draft = "Yes."
            )
        )
    }

    @Test
    fun bulletRequestsAreDetectedWithoutCloudSemantics() {
        val result = ReplyCompletenessCoach.assess(
            incomingMessage = "Please send the revised contract\n- confirm Friday's deadline\n- include the pricing sheet",
            draft = "I attached the revised contract and the pricing sheet."
        )

        assertNotNull(result)
        assertEquals(3, result!!.requestCount)
        assertEquals(2, result.answeredCount)
        assertTrue(result.missingTopics.any { "friday" in it && "deadline" in it })
    }

    @Test
    fun coordinatedQuestionsCanBothBeRecognized() {
        val result = ReplyCompletenessCoach.assess(
            incomingMessage = "What time is the meeting and where should I park?",
            draft = "The meeting starts at 3 and I'll use the north parking lot."
        )

        assertNotNull(result)
        assertEquals(2, result!!.requestCount)
        assertEquals(2, result.answeredCount)
        assertFalse(result.shouldWarn)
    }

    @Test
    fun assessmentDoesNotRetainWholeSourceMessages() {
        val incoming = "Can you confirm project ORCHID-917 and send the private invoice?"
        val draft = "The project is confirmed, but I have not sent the invoice yet."
        val result = ReplyCompletenessCoach.assess(incoming, draft)

        assertNotNull(result)
        val rendered = result.toString()
        assertFalse(rendered.contains(incoming))
        assertFalse(rendered.contains(draft))
    }
}
