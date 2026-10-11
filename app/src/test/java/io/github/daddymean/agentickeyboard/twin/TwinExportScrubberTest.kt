package io.github.daddymean.agentickeyboard.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KEYBOARD-024 export-scrub contract (Keith's security decision, chat 2026-10-11 08:09 PT):
 * slice 3 must run [TwinExportScrubber.scrub] on every entry before export. Pinned now,
 * before export exists.
 */
class TwinExportScrubberTest {

    @Test
    fun credentialsAndSecretsAreNeverExported() {
        listOf(
            "my password is hunter2", "pin for my card is 1234", "my TOTPs are 12-34", "what's the code",
            "token ghp_abcdefghijklmnopqrstuvwxyz0123", "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----",
            "AB123456", "   "
        ).forEach { assertNull(it, TwinExportScrubber.scrub(it)) }
    }

    @Test
    fun exportIsStricterThanCapture() {
        // Capture keeps lower-case words after a plain label; export blanks them.
        assertEquals("my member id is abcdef", TwinTextFilter.prepare("my member id is abcdef"))
        assertEquals("my member id is [ID]", TwinExportScrubber.scrub("my member id is abcdef"))
        assertEquals("booking reference [ID]", TwinExportScrubber.scrub("booking reference qwerty"))
        // Shorter letters-and-digits tokens and 4+ digit numbers are masked too.
        assertEquals("gate [ID] at [NUM]", TwinExportScrubber.scrub("gate B12C at 4471"))
        assertEquals("call [NUM] later", TwinExportScrubber.scrub("call 12 34 56 later"))
        val out = TwinExportScrubber.scrub("Card is 4111 1111 1111 1111, mail me at dad@example.com, member ID is ABCDEF")!!
        listOf("4111", "dad@example.com", "ABCDEF").forEach { assertFalse(out, out.contains(it)) }
    }

    @Test
    fun capturedEntriesScrubToNoDigitRunsOrIdentifiers() {
        val captured = listOf(
            "my employee ID is 123456", "passport number AB123456", "booking ref: qx-7z9p.", "ticket ZQXW",
            "445566 is my member number", "it's AB123456 btw", "see you at 7", "We have 3 kids and 2 dogs"
        ).mapNotNull { TwinTextFilter.prepare(it) }
        val digitRun = Regex("\\d{4,}")
        for (c in captured) {
            val out = TwinExportScrubber.scrub(c)!!
            assertFalse(out, digitRun.containsMatchIn(out))
            assertEquals("idempotent: $c", out, TwinExportScrubber.scrub(out))
        }
        assertEquals("see you at 7", TwinExportScrubber.scrub("see you at 7"))
        assertTrue(TwinExportScrubber.scrub("Love you, see you Sunday \u2764\uFE0F") != null)
    }
}
