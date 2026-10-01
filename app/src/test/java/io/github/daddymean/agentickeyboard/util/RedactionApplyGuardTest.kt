package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactionApplyGuardTest {

    @Test
    fun `a round-trip result echoing a sanitizer marker is blocked`() {
        // The regression behind issue #78: the draft's real number must not become a marker.
        val draft = "call me at 555-123-4567"
        val sentToCloud = CloudTextSanitizer.sanitize(draft).text
        assertTrue(sentToCloud.contains("[REDACTED_PHONE]"))
        val modelResult = "Call me at [REDACTED_PHONE]."
        assertEquals(setOf("PHONE"), RedactionApplyGuard.introducedMarkers(draft, modelResult))
    }

    @Test
    fun `every sanitizer marker kind is recognised`() {
        val draft = "mail a@b.co, order 123456789, see https://x.io/a, password=hunter2"
        val sentToCloud = CloudTextSanitizer.sanitize(draft).text
        val kinds = RedactionApplyGuard.introducedMarkers(draft, sentToCloud)
        assertEquals(setOf("EMAIL", "NUMERIC_ID", "URL", "SECRET"), kinds)
    }

    @Test
    fun `clean results and the user's own markers pass`() {
        assertTrue(RedactionApplyGuard.introducedMarkers("call me", "Call me.").isEmpty())
        assertTrue(RedactionApplyGuard.introducedMarkers("", "").isEmpty())
        val own = "Docs use [REDACTED_EMAIL] as a sample."
        assertTrue(RedactionApplyGuard.introducedMarkers(own, own.uppercase()).isEmpty())
    }

    @Test
    fun `a marker added beyond the user's own count is blocked`() {
        val source = "Sample: [REDACTED_EMAIL]. Write to bob@example.com"
        val result = "Sample: [REDACTED_EMAIL]. Write to [REDACTED_EMAIL]."
        assertEquals(setOf("EMAIL"), RedactionApplyGuard.introducedMarkers(source, result))
    }

    @Test
    fun `model-mangled markers are still caught`() {
        assertEquals(setOf("PHONE"), RedactionApplyGuard.introducedMarkers("x", "Ruf an: [redacted phone]"))
        assertEquals(setOf("NUMERIC_ID"), RedactionApplyGuard.introducedMarkers("x", "[ REDACTED_NUMERIC_ID ]"))
    }

    @Test
    fun `blocked message names what was hidden`() {
        val msg = RedactionApplyGuard.blockedMessage(sortedSetOf("NUMERIC_ID", "PHONE"))
        assertTrue(msg, msg.contains("numeric id, phone"))
    }
}
