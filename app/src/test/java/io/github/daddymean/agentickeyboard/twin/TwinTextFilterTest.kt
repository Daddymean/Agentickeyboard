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

    /**
     * Codex security review on 43a7b02 (r4239700720): credentials phrased naturally must
     * not be stored. Any message that names a credential is dropped whole.
     */
    @Test
    fun naturalLanguagePasswordDisclosuresAreDropped() {
        val disclosures = listOf(
            "my password is hunter2",
            "My Password is Hunter2!",
            "the wifi password is sunflower88",
            "passcode for the door is 4471",
            "use pw tiger55 to log in",
            "the PIN is 1234",
            "my pin is 1234",
            "card pin 9921",
            "garage code is 55210",
            "the code is 482913",
            "OTP 739201",
            "verification code: 551203",
            "2FA code 118822 hurry",
            "my seed phrase is apple banana cherry",
            "security answer is fluffy",
            "the combination is 12-34-56",
            "CVV 123",
            "login is dad, passphrase correct horse battery staple",
            // Codex review on 6de6407 (r4239778219): prepositional PIN forms. Failed on 6de6407.
            "pin for my card is 1234",
            "Pin to the safe is 4471",
            "the pin on my phone: 2580",
            "pins for the kids' tablets are 1111 and 2222",
            "code for the alarm is 8812",
        )
        disclosures.forEach { assertNull(it, TwinTextFilter.prepare(it)) }
        // Ordinary uses of nearby words stay.
        listOf(
            "Pin the post to the top",
            "Can you pass the salt",
            "The code review went well",
            "I'll code it tonight",
            "Happy to help with the login page design",
        ).forEach { assertEquals(it, TwinTextFilter.prepare(it)) }
    }
}
