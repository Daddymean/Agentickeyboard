package io.github.daddymean.agentickeyboard.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwinTextFilterTest {

    @Test
    fun anyCredentialDropsTheWholeMessage() {
        val secrets = listOf(
            "here's the key sk-abcdefghijklmnop1234 use it",
            "token ghp_abcdefghijklmnopqrstuvwxyz0123",
            "my password: hunter2 don't share",
            "api_key=AIzaSyA1234567890abcdefghijklmnop",
            "Authorization: Bearer abcdefghijklmnop123456",
            "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.abcdefghijk123",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----",
            "aws AKIAABCDEFGHIJKLMNOP"
        )
        secrets.forEach { assertNull(it, TwinTextFilter.prepare(it)) }
    }

    @Test
    fun personalNumbersAndAddressesAreMaskedNotStored() {
        val out = TwinTextFilter.prepare("Card is 4111 1111 1111 1111, mail me at dad@example.com or call 555-123-4567")!!
        assertFalse(out.contains("4111"))
        assertFalse(out.contains("dad@example.com"))
        assertFalse(out.contains("555-123-4567"))
        assertTrue(out.startsWith("Card is [REDACTED_FINANCIAL]"))
    }

    @Test
    fun ordinaryMessagesAndEmojiAreKept() {
        assertEquals("Love you buddy, see you Saturday!", TwinTextFilter.prepare("  Love you buddy, see you Saturday!  "))
        assertEquals("\u2764\uFE0F\uD83D\uDE02", TwinTextFilter.prepare("\u2764\uFE0F\uD83D\uDE02"))
    }

    @Test
    fun markerOnlyAndBlankTextIsDropped() {
        assertNull(TwinTextFilter.prepare("dad@example.com"))
        assertNull(TwinTextFilter.prepare("   "))
    }

    @Test
    fun longTextIsBounded() {
        val out = TwinTextFilter.prepare("word ".repeat(2_000))!!
        assertEquals(TwinTextFilter.MAX_CHARS, out.length)
    }
}
