package io.github.daddymean.agentickeyboard.util

import android.content.ClipDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardSensitivityTest {

    @Test
    fun `key matches the API 33 platform constant and the legacy literal`() {
        assertEquals(ClipDescription.EXTRA_IS_SENSITIVE, ClipboardSensitivity.EXTRA_IS_SENSITIVE)
        assertEquals("android.content.extra.IS_SENSITIVE", ClipboardSensitivity.EXTRA_IS_SENSITIVE)
    }

    @Test
    fun `flagged clip is sensitive`() {
        val extras = mapOf(ClipboardSensitivity.EXTRA_IS_SENSITIVE to true)
        assertTrue(ClipboardSensitivity.isFlaggedSensitive { extras[it] == true })
    }

    @Test
    fun `unflagged or explicitly false clip is not sensitive`() {
        assertFalse(ClipboardSensitivity.isFlaggedSensitive { false })
        val extras = mapOf(ClipboardSensitivity.EXTRA_IS_SENSITIVE to false)
        assertFalse(ClipboardSensitivity.isFlaggedSensitive { extras[it] == true })
    }

    @Test
    fun `unreadable extras are treated as sensitive`() {
        assertTrue(ClipboardSensitivity.isFlaggedSensitive { throw SecurityException("denied") })
    }

    @Test
    fun `history policy rejects a flagged clip even when its text looks harmless`() {
        val decision = ClipboardHistoryPolicy.evaluate("see you at noon", flaggedSensitive = true)
        assertEquals(
            ClipboardCaptureDecision.Reject(ClipboardRejectReason.FLAGGED_SENSITIVE),
            decision
        )
        assertTrue(
            ClipboardHistoryPolicy.evaluate("see you at noon") is ClipboardCaptureDecision.Accept
        )
    }
}
