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
            // Codex review on 2cfae3f (r4239826927): the value before the label. Failed on 2cfae3f.
            "1234 is the code",
            "2580 = my pin",
            "4471 is the pin for the gate",
            "it's 9921, that's the passcode",
        )
        disclosures.forEach { assertNull(it, TwinTextFilter.prepare(it)) }
        // Ordinary uses of nearby words stay.
        listOf(
            "Can you pass the salt",
        ).forEach { assertEquals(it, TwinTextFilter.prepare(it)) }
    }

    /**
     * Codex review on a401124 (r4239848795) and the Hax decision of 2026-10-10 18:37 PT:
     * a credential word together with any digit anywhere drops the message, in any
     * order or format. Failed on a401124.
     */
    @Test
    fun credentialWordWithAnyDigitIsDropped() {
        listOf(
            "12-34 is the code",
            "AB12-CD34 is the code",
            "code: 4 8 2 9",
            "my pin? 0 0 1 2",
            "the combination is twelve 34",
            "Login: dad / x9",
            "2FA",
            "pin1234",
            "1234code",
        ).forEach { assertNull(it, TwinTextFilter.prepare(it)) }
        // Accepted false positives (documented): harmless sentences with a credential word and a digit.
        assertNull(TwinTextFilter.prepare("code review covers 300 lines"))
        assertNull(TwinTextFilter.prepare("Pin 2 posts to the top"))
    }

    /** Fuzz: every credential word, value format, order and case combination is dropped. */
    @Test
    fun credentialWordDigitFuzzIsAlwaysDropped() {
        val words = listOf(
            "code", "codes", "pin", "pins", "password", "passwords", "passcode", "passphrase", "combination",
            "combo", "otp", "2fa", "cvv", "cvc", "security answer", "seed phrase", "recovery phrase", "login", "logins", "pwd"
        )
        val values = listOf("1234", "12-34", "1 2 3 4", "AB12-CD34", "4.8.2.9", "x9", "twelve 34", "#7", "0", "١٢٣", "1_2")
        val orders = listOf<(String, String) -> String>(
            { w, v -> "$w $v" },
            { w, v -> "$v is the $w" },
            { w, v -> "my $w? $v" },
            { w, v -> "($v) <- $w" },
            { w, v -> "$v, that's my ${w.uppercase()} ok" },
            { w, v -> "the ${w.replaceFirstChar { it.uppercase() }} for the gate:\n$v" },
            { w, v -> "remember: $w...and then much later in this long message, the number $v." },
        )
        val rng = java.util.Random(24)
        var checked = 0
        for (w in words) for (v in values) for (o in orders) {
            val msg = o(w, v)
            assertNull(msg, TwinTextFilter.prepare(msg))
            checked++
        }
        // Random separators between digits.
        val seps = listOf("-", " ", ".", "/", "_", ", ", " - ", "\t")
        repeat(500) {
            val digits = (1..(1 + rng.nextInt(8))).map { rng.nextInt(10).toString() }
            val v = digits.joinToString(seps[rng.nextInt(seps.size)])
            val w = words[rng.nextInt(words.size)]
            val msg = if (rng.nextBoolean()) "$w $v" else "$v $w"
            assertNull(msg, TwinTextFilter.prepare(msg))
            checked++
        }
        assertTrue(checked > 2000)
    }

    /**
     * Codex review on 7502783 (r4239941592) and the Hax decision of 18:37 PT ("if the
     * credential filter is hit again, drop any message with a credential word, digits or
     * not"): every credential word, in any case, singular or plural, drops the message.
     * Failed on 7502783.
     */
    @Test
    fun anyCredentialWordDropsTheMessageDigitsOrNot() {
        listOf("my TOTPs are 12-34", "MFAs: x9", "2FAs are on", "Pin the post to the top", "send me the wifi password",
            "what's the code", "I'll code it tonight", "Happy to help with the login page design").forEach {
            assertNull(it, TwinTextFilter.prepare(it))
        }
        val words = listOf(
            "code", "pin", "password", "passwd", "pwd", "pw", "passcode", "passphrase", "passkey", "combination", "combo",
            "otp", "2fa", "mfa", "totp", "cvv", "cvv2", "cvc", "csc", "security answer", "security question",
            "seed phrase", "recovery phrase", "backup code", "mnemonic", "login", "log-in", "logon", "sign-in", "username"
        )
        val forms = listOf<(String) -> String>({ it }, { it + "s" }, { it + "es" }, { "$it's" }, { "$it\u2019s" }, { it + "s'" })
        val cases = listOf<(String) -> String>({ it }, { it.uppercase() }, { it.replaceFirstChar { c -> c.uppercase() } })
        var n = 0
        for (w in words) for (f in forms) for (c in cases) for (frame in listOf("%s", "the %s, ok", "(%s)", "%s!", "about my %s tomorrow")) {
            val msg = frame.format(c(f(w)))
            assertNull(msg, TwinTextFilter.prepare(msg))
            n++
        }
        assertTrue(n > 2_500)
        // Words that merely contain the letters stay: the match is whole-word.
        listOf("Can you pass the salt", "Pinterest board is cute", "Spinning class at six", "The decoder ring arrived")
            .forEach { assertEquals(it, TwinTextFilter.prepare(it)) }
    }

    /**
     * Codex review on da29f67 (r4239989012): a labeled short or alphanumeric identifier
     * ("my employee ID is 123456", "passport number AB123456") was stored unmasked.
     * Conservative rule: an identifier label plus any digit anywhere, or any unlabeled
     * letters-and-digits token of 6+ characters, drops the whole message. Failed on da29f67.
     */
    @Test
    fun labeledOrAlphanumericIdentifiersAreDropped() {
        listOf(
            "my employee ID is 123456", "passport number AB123456", "Passport: X1234567",
            "driver's license D1234-5678", "my licence no. 99 1234", "SSN 123-45-6789 for the form",
            "member # 4471", "policy number 12-3456", "account 0012 3344", "routing 021000021",
            "IBAN DE89 3704", "student id 20231234", "patient MRN 445566", "VIN 1HGCM82633A004352",
            "booking ref QX7Z9P", "my national insurance number is QQ 12 34 56 C", "tax id 12-3456789",
            "the serial is SN 4471 2", "his badge number is 42", "ref ABC123 please"
        ).forEach { assertNull(it, TwinTextFilter.prepare(it)) }
        // Unlabeled letters-and-digits tokens of 6+ characters (passport, ticket, plate shapes).
        listOf("it's AB123456 btw", "use X1234567", "QX7Z9P is the one", "7ABC123 out front").forEach {
            assertNull(it, TwinTextFilter.prepare(it))
        }
        val labels = listOf(
            "id", "ID", "identification", "identifier", "passport", "license", "licence", "ssn", "social security",
            "social insurance", "national insurance", "nhs", "tin", "itin", "ein", "tax id", "vat", "iban", "routing",
            "swift", "acct", "account", "member", "membership", "policy", "customer", "patient", "mrn", "medical record",
            "student", "employee", "staff", "badge", "serial", "vin", "plate", "registration", "reference", "ref",
            "booking", "confirmation", "tracking", "visa", "permit", "case", "claim", "ticket", "number", "no."
        )
        val values = listOf("123456", "42", "AB123456", "12-34", "9 9", "x7")
        var n = 0
        for (l in labels) for (v in values) for (frame in listOf("my %s is %s", "%s: %s", "%2\$s is my %1\$s", "%s #%s")) {
            val msg = frame.format(l, v)
            assertNull(msg, TwinTextFilter.prepare(msg))
            n++
        }
        assertTrue(n > 1_000)
        // Without a digit the labels alone stay, and ordinary short numbers stay.
        listOf("The ticket line was long", "Call me after the case review", "We have 3 kids and 2 dogs", "see you at 7")
            .forEach { assertEquals(it, TwinTextFilter.prepare(it)) }
    }
}
