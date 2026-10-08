package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.CloudTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudTextSanitizerTest {

    @Test
    fun redactsCommonSensitiveValues() {
        val input = """
            Email me at buyer@example.com or call (555) 867-5309.
            SSN 123-45-6789, card 4111 1111 1111 1111,
            server 192.168.1.42, order 1234567890,
            link https://example.com/private?id=123 and api_key=super-secret-value.
        """.trimIndent()

        val result = CloudTextSanitizer.sanitize(input)

        assertTrue(result.changed)
        assertTrue(result.replacements >= 8)
        assertTrue(result.text.contains("[REDACTED_EMAIL]"))
        assertTrue(result.text.contains("[REDACTED_PHONE]"))
        assertTrue(result.text.contains("[REDACTED_SSN]"))
        assertTrue(result.text.contains("[REDACTED_FINANCIAL]"))
        assertTrue(result.text.contains("[REDACTED_IP]"))
        assertTrue(result.text.contains("[REDACTED_NUMERIC_ID]"))
        assertTrue(result.text.contains("[REDACTED_URL]"))
        assertTrue(result.text.contains("api_key=[REDACTED_SECRET]"))
        assertFalse(result.text.contains("buyer@example.com"))
        assertFalse(result.text.contains("super-secret-value"))
    }

    @Test
    fun preservesOrdinaryWriting() {
        val input = "Please meet me at 4:30 tomorrow. Bring 12 blue folders."

        val result = CloudTextSanitizer.sanitize(input)

        assertFalse(result.changed)
        assertEquals(0, result.replacements)
        assertEquals(input, result.text)
    }

    @Test
    fun keepsSerializedJsonStructurallyIntact() {
        val input = """{"contents":[{"parts":[{"text":"Contact jane@example.com about invoice 987654321."}]}]}"""

        val result = CloudTextSanitizer.sanitize(input)

        assertEquals(
            """{"contents":[{"parts":[{"text":"Contact [REDACTED_EMAIL] about invoice [REDACTED_NUMERIC_ID]."}]}]}""",
            result.text
        )
        assertEquals(2, result.replacements)
    }

    @Test
    fun blankInputIsReturnedWithoutWork() {
        assertEquals("", CloudTextSanitizer.sanitize("").text)
        assertEquals(0, CloudTextSanitizer.sanitize("   ").replacements)
    }

    // --- Per-rule coverage (from #89) ---

    @Test
    fun redactsCredentialAssignmentsInEverySpelling() {
        val cases = mapOf(
            "password=my_secret_pwd" to "my_secret_pwd",
            "passcode: 123456" to "123456",
            "api_key = 'abcdef12345'" to "abcdef12345",
            "access-token:\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\"" to "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
            "auth_token=supersecret123" to "supersecret123",
            "secret : my-secret-value" to "my-secret-value"
        )

        cases.forEach { (input, secret) ->
            val result = CloudTextSanitizer.sanitize(input)
            assertTrue(input, result.changed)
            assertTrue(input, result.text.contains("=[REDACTED_SECRET]"))
            assertFalse(input, result.text.contains(secret))
        }
    }

    @Test
    fun credentialRuleConsumesMatchingQuotesWithoutLeavingAStrayOne() {
        assertRedacts(
            "my password: \"hunter2\"" to "my password=[REDACTED_SECRET]",
            "api_key = 'x'" to "api_key=[REDACTED_SECRET]",
            "token check: secret=\"a b c\", next" to "token check: secret=[REDACTED_SECRET], next",
            "access_token:'abc;def' done" to "access_token=[REDACTED_SECRET] done",
            "password: \"a\" and \"b\"" to "password=[REDACTED_SECRET] and \"b\""
        )
    }

    @Test
    fun credentialRuleHandlesUnbalancedOrEmptyQuotesSafely() {
        // Unterminated quote: the bare-token fallback still redacts the value.
        val open = CloudTextSanitizer.sanitize("password: \"hunter2 and more")
        assertEquals("password=[REDACTED_SECRET] and more", open.text)
        // A quote that is not closed on the same line does not swallow the next line.
        val multi = CloudTextSanitizer.sanitize("secret: \"abc\nnext line\"")
        assertFalse(multi.text.contains("abc"))
        assertTrue(multi.text.contains("next line"))
        // Mismatched quotes are not treated as a pair; the value is still redacted.
        assertEquals("api_key=[REDACTED_SECRET]'", CloudTextSanitizer.sanitize("api_key=\"xyz'").text)
        // Empty quotes have nothing to redact.
        assertEquals("password: \"\"", CloudTextSanitizer.sanitize("password: \"\"").text)
    }

    @Test
    fun redactsEmails() {
        assertRedacts(
            "My email is user.name+tag@example.co.uk" to "My email is [REDACTED_EMAIL]",
            "Contact info@company.com today." to "Contact [REDACTED_EMAIL] today.",
            "test_123@sub.domain.org" to "[REDACTED_EMAIL]"
        )
    }

    @Test
    fun redactsCardNumbersAndLeavesShorterRunsToTheNumericRule() {
        assertRedacts(
            "Card: 4111 1111 1111 1111" to "Card: [REDACTED_FINANCIAL]",
            "Visa: 4111-1111-1111-1111" to "Visa: [REDACTED_FINANCIAL]",
            "Amex: 341234567890123" to "Amex: [REDACTED_FINANCIAL]",
            "No spaces: 4111111111111111" to "No spaces: [REDACTED_FINANCIAL]",
            "Too short: 123456789012" to "Too short: [REDACTED_NUMERIC_ID]"
        )
    }

    @Test
    fun redactsSsn() {
        assertRedacts(
            "My SSN is 123-45-6789" to "My SSN is [REDACTED_SSN]",
            "SSN: 987-65-4321." to "SSN: [REDACTED_SSN]."
        )
    }

    @Test
    fun redactsNumericIdsOfEightOrMoreDigitsOnly() {
        assertRedacts(
            "Order 12345678" to "Order [REDACTED_NUMERIC_ID]",
            "claim 9876543210" to "claim [REDACTED_NUMERIC_ID]",
            "short 1234567" to "short 1234567"
        )
    }

    @Test
    fun redactsPhoneNumberFormats() {
        assertRedacts(
            "Call (555) 123-4567" to "Call [REDACTED_PHONE]",
            "Mobile: 555-123-4567" to "Mobile: [REDACTED_PHONE]",
            "Intl: +1 555 123 4567" to "Intl: [REDACTED_PHONE]",
            "Dots: 555.123.4567" to "Dots: [REDACTED_PHONE]",
            "Spaced: 555 123 4567" to "Spaced: [REDACTED_PHONE]"
        )
    }

    @Test
    fun redactsValidIpv4AddressesOnly() {
        assertRedacts(
            "Local: 192.168.1.1" to "Local: [REDACTED_IP]",
            "Google: 8.8.8.8" to "Google: [REDACTED_IP]",
            "Max: 255.255.255.255" to "Max: [REDACTED_IP]",
            "Not an IP: 256.256.256.256" to "Not an IP: 256.256.256.256"
        )
    }

    @Test
    fun redactsUrls() {
        assertRedacts(
            "Visit http://example.com" to "Visit [REDACTED_URL]",
            "Secure https://test.org/path?q=1" to "Secure [REDACTED_URL]",
            "FTP ftp://files.server.net/folder" to "FTP [REDACTED_URL]"
        )
    }

    // Fake, non-functional tokens shaped like the real ones (GEMINI-KEY-REVIEW-001 F1).
    @Test
    fun redactsBareCredentialTokens() {
        assertRedacts(
            "fix this AIzaSyA1b2C3d4E5f6G7h8I9j0KlMnOpQrStUvW please" to
                "fix this [REDACTED_SECRET] please",
            "token ghp_abcdefghijklmnopqrstuvwxyz0123456789 here" to
                "token [REDACTED_SECRET] here",
            "key sk-proj-abcdefghijklmnop1234 ok" to "key [REDACTED_SECRET] ok",
            "aws AKIAABCDEFGHIJKLMNOP done" to "aws [REDACTED_SECRET] done",
            "jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U end" to
                "jwt [REDACTED_SECRET] end",
            "Authorization: Bearer abc123DEF456ghi789" to "Authorization: Bearer [REDACTED_SECRET]"
        )
    }

    // Hax review 5451548067: families that leaked whole or were only partly masked.
    @Test
    fun redactsFurtherProviderTokenFamilies() {
        assertRedacts(
            "pat github_pat_FAKE0000000000000000000_notarealtokenatall end" to
                "pat [REDACTED_SECRET] end",
            "slack xoxb-FAKE000000-FAKE000000000-NotARealSlackToken end" to
                "slack [REDACTED_SECRET] end",
            "gl glpat-FAKEFAKEFAKEFAKEFAKE end" to "gl [REDACTED_SECRET] end",
            "stripe sk_live_FAKE0000FAKE0000FAKE and rk_test_FAKE1111FAKE1111FAKE end" to
                "stripe [REDACTED_SECRET] and [REDACTED_SECRET] end",
            "oauth ya29.FAKE_not_a_real_oauth_token end" to "oauth [REDACTED_SECRET] end",
            "-----BEGIN DSA PRIVATE KEY-----\nMIIBuwIBAAKBgQ\n-----END DSA PRIVATE KEY----- after" to
                "[REDACTED_SECRET] after"
        )
    }

    @Test
    fun bareTokenDigitsAreNotHalfMatchedAsAPhoneNumber() {
        val result = CloudTextSanitizer.sanitize("AIzaSy5551234567abcdefghijklmnopqrstu")

        assertEquals("[REDACTED_SECRET]", result.text)
        assertEquals(1, result.replacements)
    }

    @Test
    fun redactsWholePemPrivateKeyIncludingAnUnterminatedOne() {
        val pem = "-----BEGIN PRIVATE KEY-----\nMIIEvQIBADANBgkqhkiG9w0BAQEFAASC\n-----END PRIVATE KEY-----"
        assertRedacts(
            "here: $pem thanks" to "here: [REDACTED_SECRET] thanks",
            "-----BEGIN RSA PRIVATE KEY-----\nMIIEpAIBAAKCAQEA truncated" to "[REDACTED_SECRET]"
        )
    }

    @Test
    fun leavesTokenLikeOrdinaryWordsAlone() {
        val input = "Ask the skeptic about sk-8 skates, task_live_demo, the ghp team, the xox game and an AIza sign."

        val result = CloudTextSanitizer.sanitize(input)

        assertFalse(result.changed)
        assertEquals(input, result.text)
    }

    private fun assertRedacts(vararg cases: Pair<String, String>) {
        cases.forEach { (input, expected) ->
            assertEquals(input, expected, CloudTextSanitizer.sanitize(input).text)
        }
    }
}
