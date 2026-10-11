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
     * Codex reviews on da29f67 and 6b12a27 (r4239989012, r4240024848) and Keith's security
     * decision (chat, 2026-10-11 08:09 PT): identifiers are **blanked, not dropped**, best
     * effort. A recognised value after an identifier label, with or without digits, and
     * any unlabeled letters-and-digits token of 6+ characters become [ID]; the rest of the
     * message is still learned. Failed on 6b12a27 (the messages were dropped).
     */
    @Test
    fun labeledAndAlphanumericIdentifiersAreBlanked() {
        assertEquals("booking reference [ID]", TwinTextFilter.prepare("booking reference QWERTY"))
        assertEquals("member ID is [ID]", TwinTextFilter.prepare("member ID is ABCDEF"))
        assertEquals("my member ID is [ID]", TwinTextFilter.prepare("my member ID is ABCDEF"))
        assertEquals("my employee ID is [ID]", TwinTextFilter.prepare("my employee ID is 123456"))
        assertEquals("passport number [ID], see you there", TwinTextFilter.prepare("passport number AB123456, see you there"))
        assertEquals("ref [ID] please", TwinTextFilter.prepare("ref ABC123 please"))
        assertEquals("booking ref: [ID].", TwinTextFilter.prepare("booking ref: qx-7z9p."))
        assertEquals("it's [ID] btw", TwinTextFilter.prepare("it's AB123456 btw"))
        assertEquals("[ID] is my member number", TwinTextFilter.prepare("445566 is my member number"))
        val labeled = listOf(
            "my employee ID is 123456", "passport number AB123456", "Passport: X1234567",
            "driver's license D1234-5678", "my licence no. 99 1234", "member # 4471", "policy number 12-3456",
            "account 0012 3344", "IBAN DE89 3704", "student id 20231234", "patient MRN 445566",
            "VIN 1HGCM82633A004352", "booking ref QX7Z9P", "my national insurance number is QQ 12 34 56 C",
            "tax id 12-3456789", "the serial is SN 4471 2", "his badge number is 42", "order #A1B2C3", "ticket ZQXW"
        )
        val secretParts = Regex("[A-Z]*\\d[\\dA-Z-]*|QWERTY|ZQXW|MRN|QQ|SN")
        for (msg in labeled) {
            val out = TwinTextFilter.prepare(msg)
            // Long digit runs may already be masked by the cloud redactor's own marker.
            assertTrue("$msg -> $out", out != null && (out.contains("[ID]") || out.contains("[REDACTED_")))
            secretParts.findAll(msg).forEach { assertFalse("$msg -> $out", out!!.contains(it.value)) }
        }
        val labels = listOf(
            "id", "ID", "identification", "passport", "license", "licence", "ssn", "social security number",
            "national insurance", "nhs", "tax id", "vat", "iban", "routing", "acct", "account", "member", "membership",
            "policy", "customer", "patient", "mrn", "medical record", "student", "employee", "badge", "serial", "vin",
            "plate", "registration", "reference", "ref", "booking", "reservation", "confirmation", "tracking", "visa",
            "permit", "claim", "ticket", "order", "invoice", "member ID", "account number", "booking reference"
        )
        val values = listOf("123456", "42", "AB123456", "12-34", "QWERTY", "X7", "ZK-99")
        var n = 0
        for (l in labels) for (v in values) for (frame in listOf("my %s is %s", "%s: %s", "%s #%s", "%s %s thanks", "%2\$s is my %1\$s")) {
            val msg = frame.format(l, v)
            val out = TwinTextFilter.prepare(msg)
            assertTrue("$msg -> $out", out != null && (out.contains("[ID]") || out.contains("[REDACTED_")) && !out.contains(v))
            n++
        }
        assertTrue(n > 1_500)
        // Credentials are still dropped whole, never just blanked.
        listOf("my password is QWERTY", "pin for my card is 1234", "my TOTPs are 12-34", "login ABC123", "member code 4471")
            .forEach { assertNull(it, TwinTextFilter.prepare(it)) }
        // Ordinary text is untouched.
        listOf("The ticket line was long", "in case 2 people come", "We have 3 kids and 2 dogs", "see you at 7",
            "my booking is tomorrow", "My order is late again")
            .forEach { assertEquals(it, TwinTextFilter.prepare(it)) }
        // A message that is only an identifier is not learned.
        assertNull(TwinTextFilter.prepare("AB123456"))
    }

    /**
     * Codex review on 8ff181a (r4242126136): "my access token is violetgarden" was stored.
     * Per the standing rule (dropping is the safe default when the credential filter is
     * hit again), token, key, secret and credential words now drop the message too.
     * Failed on 8ff181a.
     */
    @Test
    fun tokenKeyAndSecretDisclosuresAreDropped() {
        listOf(
            "my access token is violetgarden", "the refresh token is violetgarden", "API token is violetgarden",
            "auth token: violetgarden", "my api key is violetgarden", "the private key is violetgarden",
            "the key is violetgarden", "secret is violetgarden", "client secret violetgarden",
            "my credentials are bob and violetgarden", "bearer violetgarden", "the JWT is violetgarden",
            "ssh passphrase violetgarden", "license key violetgarden", "my apikey violetgarden"
        ).forEach { assertNull(it, TwinTextFilter.prepare(it)) }
        val words = listOf(
            "token", "access token", "refresh token", "auth token", "api token", "session token", "bearer token",
            "key", "api key", "apikey", "api-key", "private key", "secret key", "access key", "license key",
            "product key", "secret", "client secret", "credential", "cred", "bearer", "jwt", "oauth",
            "auth", "session id", "cookie"
        )
        val forms = listOf<(String) -> String>({ it }, { it + "s" }, { "$it's" }, { it.uppercase() }, { it.replaceFirstChar { c -> c.uppercase() } })
        var n = 0
        for (w in words) for (f in forms) for (frame in listOf("my %s is violetgarden", "%s: violetgarden", "violetgarden is the %s", "(%s) violetgarden")) {
            val msg = frame.format(f(w))
            assertNull(msg, TwinTextFilter.prepare(msg))
            n++
        }
        assertTrue(n > 500)
        listOf("Monkey business tonight", "the turkey was great", "Keyboard works now").forEach {
            assertEquals(it, TwinTextFilter.prepare(it))
        }
    }

    /**
     * Codex review on 6695c82 (r4242165268): joined credential labels ("accessToken is
     * violetgarden", "clientSecret is violetgarden") were stored. Every word is now split
     * on camelCase/PascalCase, snake_case, kebab-case, dots and digits, and the lower-cased
     * word is also checked for credential stems. Failed on 6695c82.
     */
    @Test
    fun joinedCredentialLabelsAreDropped() {
        listOf(
            "accessToken is violetgarden", "clientSecret=violetgarden", "my_api_key violetgarden",
            "X-Auth-Token: violetgarden", "AWS_SECRET_ACCESS_KEY=violetgarden", "sessionid violetgarden",
            "passwd violetgarden", "myPassword is violetgarden", "PIN-code 4471", "privateKey is violetgarden",
            "refresh.token violetgarden", "apiKey2 violetgarden", "userPin 4471", "OTPcode 123"
        ).forEach { assertNull(it, TwinTextFilter.prepare(it)) }
    }

    /** Property-style: every joined spelling of a credential label drops the message. */
    @Test
    fun joinedCredentialLabelVariantsAreAlwaysDropped() {
        val labels = listOf(
            listOf("access", "token"), listOf("auth", "token"), listOf("refresh", "token"), listOf("id", "token"),
            listOf("client", "secret"), listOf("secret", "access", "key"), listOf("private", "key"), listOf("api", "key"),
            listOf("password"), listOf("pass", "code"), listOf("user", "pin"), listOf("pin", "code"), listOf("session", "id"),
            listOf("bearer"), listOf("jwt"), listOf("oauth", "token"), listOf("cookie"), listOf("otp"), listOf("mfa", "code"),
            listOf("2fa", "code"), listOf("cvv"), listOf("cvc"), listOf("seed", "words"), listOf("mnemonic"), listOf("credential"),
            listOf("db", "password"), listOf("wifi", "pwd"), listOf("login", "code")
        )
        fun cap(w: String) = w.replaceFirstChar { it.uppercase() }
        val joins = listOf<(List<String>) -> String>(
            { it.first() + it.drop(1).joinToString("") { w -> cap(w) } },   // camelCase
            { it.joinToString("") { w -> cap(w) } },                        // PascalCase
            { it.joinToString("_") },                                       // snake_case
            { it.joinToString("_").uppercase() },                           // SCREAMING_SNAKE
            { it.joinToString("-") },                                       // kebab-case
            { "X-" + it.joinToString("-") { w -> cap(w) } },                // header style
            { it.joinToString(".") },                                       // dotted
            { it.joinToString("") },                                        // concatenated
            { it.joinToString("").uppercase() + "2" },                      // with digits
            { "my" + it.joinToString("") { w -> cap(w) } }                  // prefixed
        )
        val frames = listOf("%s is violetgarden", "%s=violetgarden", "%s: violetgarden", "\"%s\": \"violetgarden\"", "set %s to violetgarden")
        var n = 0
        for (l in labels) for (j in joins) for (fr in frames) {
            val msg = fr.format(j(l))
            assertNull(msg, TwinTextFilter.prepare(msg))
            n++
        }
        assertTrue(n >= 1_400)
        // Split tokens only for "pin", so ordinary words containing it stay.
        listOf("Spinning class at six", "Pinterest board is cute", "Keyboard works now", "Happy birthday Grandpa")
            .forEach { assertEquals(it, TwinTextFilter.prepare(it)) }
    }

    /**
     * Codex review on 97339e1 (r4242257950) and the Hax decision of 2026-10-11 09:18 PT:
     * a structural rule. A message drops when (a) any word ends with a credential tail
     * (code, key, phrase, word, pin, answer, pass) joined to something else, outside a
     * small allowlist, or (b) a label with a credential stem or tail is assigned a single
     * opaque value ("X is violetgarden"). Failed on 97339e1.
     */
    @Test
    fun joinedCredentialTailsAndAssignmentsAreDropped() {
        listOf(
            "securitycode is 4471", "accesskey=violetgarden", "recoveryphrase is apple banana cherry",
            "backupcode 1234", "secretword: hunter", "mypass is x", "userpin 4471", "zipcode 94110",
            "the safe word: violetgarden", "pass -> violetgarden", "the answer was Tr0ub4dor"
        ).forEach { assertNull(it, TwinTextFilter.prepare(it)) }
        val prefixes = listOf("security", "access", "recovery", "seed", "backup", "secret", "private", "master", "admin",
            "root", "wifi", "door", "alarm", "safe", "vault", "unlock")
        val tails = listOf("code", "key", "phrase", "word", "pin", "pass", "answer")
        val seps = listOf(" is ", "=", ": ", " was ")
        var n = 0
        for (p in prefixes) for (t in tails) for (sep in seps) for (v in listOf("violetgarden", "4471", "x9")) {
            for (label in listOf(p + t, p + t.replaceFirstChar { it.uppercase() }, (p + t).uppercase())) {
                val msg = "$label$sep$v"
                assertNull(msg, TwinTextFilter.prepare(msg))
                n++
            }
            // Spaced labels with an opaque value are caught by the assignment rule.
            if (v != "x9") {
                val msg = "my $p $t$sep$v"
                assertNull(msg, TwinTextFilter.prepare(msg))
                n++
            }
        }
        assertTrue(n > 3_000)
        listOf(
            "the monkey is cute", "hockey game at 7", "my keyword research", "crossword is hard", "barcode scanner broke",
            "Turkey sandwich: delicious", "the compass was broken", "lowkey tired today", "a catchphrase: just do it",
            "Can you pass the salt", "The answer is yes"
        ).forEach { assertEquals(it, TwinTextFilter.prepare(it)) }
    }

    /** The tail allowlist stays small and every entry is really kept; "zipcode" is not exempt. */
    @Test
    fun credentialTailAllowlistIsSmallAndPinned() {
        val allow = CredentialMentions.TAIL_ALLOWLIST
        assertTrue(allow.size <= 35)
        assertFalse("zipcode" in allow)
        for (w in allow) {
            assertEquals(w, "we talked about the $w today", TwinTextFilter.prepare("we talked about the $w today"))
        }
        assertNull(TwinTextFilter.prepare("we talked about the zipcode today"))
    }

    /**
     * Codex review on 5f7fb93 (r4242321005) and Keith's security decision (chat,
     * 2026-10-11 10:09 PT): quoted labels in assignments, and any code- or config-like
     * text, drop the whole message. Failed on 5f7fb93.
     */
    @Test
    fun codeLikeTextAndQuotedAssignmentsAreDropped() {
        listOf(
            "{\"pass\":\"hunter2\"}", "\"answer\": \"Tr0ub4dor\"", "`pwd`='x'", "'answer' = 'Tr0ub4dor'",
            "`pass`: `violetgarden`", "DB_PASS=violetgarden", "use DB_PASS=violetgarden on staging",
            "https://example.com/cb?api_key=violetgarden", "see example.com/x?id=3&sig=abc123 ok",
            "http://host/path?password=violetgarden", "[\"user\", {\"answer\": \"x\"}]",
            "```\nfoo = bar\n```", "~~~ anything ~~~", "host=db.local", "  answer = violetgarden",
            "db.answer: violetgarden", "port: 5432\nuser: admin", "server:\n  answer: x\n  port: 1",
            "theAnswer: Tr0ub4dor", "answer: Tr0ub4dor"
        ).forEach { assertNull(it, TwinTextFilter.prepare(it)) }
        listOf(
            "meet at 5: bring snacks", "ratio is 3:1", "Note: bring snacks", "Reminder: dinner at 7",
            "the score was 2-1", "love you :)", "it's 10:30 now", "Q: are we still on? A: yes"
        ).forEach { assertEquals(it, TwinTextFilter.prepare(it)) }
    }
}
