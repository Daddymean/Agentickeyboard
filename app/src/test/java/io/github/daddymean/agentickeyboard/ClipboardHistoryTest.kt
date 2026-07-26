package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.ClipboardCaptureDecision
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryLimits
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryPolicy
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryPruner
import io.github.daddymean.agentickeyboard.util.ClipboardRejectReason
import io.github.daddymean.agentickeyboard.util.ClipboardRetentionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardHistoryTest {

    @Test
    fun ordinaryTextIsAcceptedAndNormalized() {
        val result = ClipboardHistoryPolicy.evaluate("  Hello\r\nworld  ")
        assertTrue(result is ClipboardCaptureDecision.Accept)
        result as ClipboardCaptureDecision.Accept
        assertEquals("Hello\nworld", result.content)
        assertEquals(64, result.contentHash.length)
    }

    @Test
    fun duplicateTextProducesStableHash() {
        val first = ClipboardHistoryPolicy.evaluate("same text") as ClipboardCaptureDecision.Accept
        val second = ClipboardHistoryPolicy.evaluate("  same text\r\n") as ClipboardCaptureDecision.Accept
        assertEquals(first.contentHash, second.contentHash)
    }

    @Test
    fun blankAndOversizedTextAreRejected() {
        assertRejected("   ", ClipboardRejectReason.BLANK)
        assertRejected(
            "x".repeat(ClipboardHistoryLimits.MAX_CONTENT_CHARS + 1),
            ClipboardRejectReason.TOO_LONG
        )
    }

    @Test
    fun credentialsAndPrivateKeysAreRejected() {
        assertRejected("password: hunter2", ClipboardRejectReason.PASSWORD_OR_PIN)
        assertRejected("Authorization: Bearer abcdefghijklmnopqrstuvwxyz", ClipboardRejectReason.AUTH_SECRET)
        assertRejected("sk-abcdefghijklmnopqrstuvwxyz123456", ClipboardRejectReason.AUTH_SECRET)
        assertRejected(
            "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----",
            ClipboardRejectReason.PRIVATE_KEY
        )
    }

    @Test
    fun verificationAndPaymentDataAreRejected() {
        assertRejected("Your verification code is 482901", ClipboardRejectReason.ONE_TIME_CODE)
        assertRejected("CVV: 123", ClipboardRejectReason.PAYMENT_SECURITY_CODE)
        assertRejected("4111 1111 1111 1111", ClipboardRejectReason.PAYMENT_CARD)
        assertRejected("SSN 123-45-6789", ClipboardRejectReason.GOVERNMENT_ID)
    }

    @Test
    fun ordinaryNumbersAreNotAutomaticallyRejected() {
        val result = ClipboardHistoryPolicy.evaluate("The warehouse has 482901 units in aisle 12")
        assertTrue(result is ClipboardCaptureDecision.Accept)
    }

    @Test
    fun recoveryPhraseWithContextIsRejected() {
        val phrase = "seed phrase: abandon ability able about above absent absorb abstract absurd abuse access accident"
        assertRejected(phrase, ClipboardRejectReason.RECOVERY_PHRASE)
    }

    @Test
    fun prunerKeepsPinnedItemsAndRemovesExpiredUnpinned() {
        val now = 20L * ClipboardHistoryLimits.DAY_MS
        val plan = ClipboardHistoryPruner.plan(
            records = listOf(
                ClipboardRetentionRecord(1, pinned = true, lastSeenAt = 0L),
                ClipboardRetentionRecord(2, pinned = false, lastSeenAt = now - 8L * ClipboardHistoryLimits.DAY_MS),
                ClipboardRetentionRecord(3, pinned = false, lastSeenAt = now)
            ),
            now = now,
            retentionDays = 7
        )
        assertEquals(setOf(2), plan.expiredIds)
        assertFalse(1 in plan.idsToDelete)
        assertFalse(3 in plan.idsToDelete)
    }

    @Test
    fun prunerUsesStableNewestFirstOverflow() {
        val records = (1..5).map { id ->
            ClipboardRetentionRecord(id, pinned = false, lastSeenAt = id.toLong())
        }
        val plan = ClipboardHistoryPruner.plan(
            records = records,
            now = ClipboardHistoryLimits.DAY_MS,
            retentionDays = 30,
            maxUnpinnedItems = 3
        )
        assertEquals(setOf(1, 2), plan.overflowIds)
        assertEquals(emptySet<Int>(), plan.expiredIds)
    }

    @Test
    fun rejectionMessagesNeverEchoClipboardContent() {
        ClipboardRejectReason.entries.forEach { reason ->
            val message = ClipboardHistoryPolicy.rejectionMessage(reason)
            assertTrue(message.isNotBlank())
            assertFalse(message.contains("hunter2"))
        }
    }

    private fun assertRejected(text: String, expected: ClipboardRejectReason) {
        val result = ClipboardHistoryPolicy.evaluate(text)
        assertTrue("Expected rejection for $expected", result is ClipboardCaptureDecision.Reject)
        assertEquals(expected, (result as ClipboardCaptureDecision.Reject).reason)
    }
}
