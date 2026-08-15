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

    @Test
    fun redactsPasswordsAndTokens() {
        val cases = listOf(
            "password=my_secret_pwd" to "password=[REDACTED_SECRET]",
            "passcode: 123456" to "passcode=[REDACTED_SECRET]",
            "api_key = 'abcdef12345'" to "api_key=[REDACTED_SECRET]'",
            "access-token:\"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\"" to "access-token=[REDACTED_SECRET]\"",
            "auth_token=supersecret123" to "auth_token=[REDACTED_SECRET]",
            "secret : my-secret-value" to "secret=[REDACTED_SECRET]"
        )

        cases.forEach { (input, expected) ->
            val result = CloudTextSanitizer.sanitize(input)
            assertEquals(expected, result.text)
            assertTrue(result.changed)
        }
    }

    @Test
    fun redactsEmails() {
        val cases = listOf(
            "My email is user.name+tag@example.co.uk" to "My email is [REDACTED_EMAIL]",
            "Contact info@company.com today." to "Contact [REDACTED_EMAIL] today.",
            "test_123@sub.domain.org" to "[REDACTED_EMAIL]"
        )

        cases.forEach { (input, expected) ->
            val result = CloudTextSanitizer.sanitize(input)
            assertEquals(expected, result.text)
            assertTrue(result.changed)
        }
    }

    @Test
    fun redactsFinancialCreditCards() {
        val cases = listOf(
            "Card: 4111 1111 1111 1111" to "Card: [REDACTED_FINANCIAL]",
            "Visa: 4111-1111-1111-1111" to "Visa: [REDACTED_FINANCIAL]",
            "Amex: 341234567890123" to "Amex: [REDACTED_FINANCIAL]", // 15 digits
            "No spaces: 4111111111111111" to "No spaces: [REDACTED_FINANCIAL]",
            "Too short: 123456789012" to "Too short: [REDACTED_NUMERIC_ID]" // 12 digits -> caught by numeric ID
        )

        cases.forEach { (input, expected) ->
            val result = CloudTextSanitizer.sanitize(input)
            assertEquals(expected, result.text)
        }
    }

    @Test
    fun redactsSSN() {
        val cases = listOf(
            "My SSN is 123-45-6789" to "My SSN is [REDACTED_SSN]",
            "SSN: 987-65-4321." to "SSN: [REDACTED_SSN]."
        )

        cases.forEach { (input, expected) ->
            val result = CloudTextSanitizer.sanitize(input)
            assertEquals(expected, result.text)
        }
    }

    @Test
    fun redactsLongNumericIds() {
        val cases = listOf(
            "Order 12345678" to "Order [REDACTED_NUMERIC_ID]",
            "claim 9876543210" to "claim [REDACTED_NUMERIC_ID]",
            "short 1234567" to "short 1234567" // Too short (7 digits, regex requires 8)
        )

        cases.forEach { (input, expected) ->
            val result = CloudTextSanitizer.sanitize(input)
            assertEquals(expected, result.text)
        }
    }

    @Test
    fun redactsPhoneNumbers() {
        val cases = listOf(
            "Call (555) 123-4567" to "Call [REDACTED_PHONE]",
            "Mobile: 555-123-4567" to "Mobile: [REDACTED_PHONE]",
            "Intl: +1 555 123 4567" to "Intl: [REDACTED_PHONE]",
            "Dots: 555.123.4567" to "Dots: [REDACTED_PHONE]",
            "Spaced: 555 123 4567" to "Spaced: [REDACTED_PHONE]"
        )

        cases.forEach { (input, expected) ->
            val result = CloudTextSanitizer.sanitize(input)
            assertEquals(expected, result.text)
        }
    }

    @Test
    fun redactsIpAddresses() {
        val cases = listOf(
            "Local: 192.168.1.1" to "Local: [REDACTED_IP]",
            "Google: 8.8.8.8" to "Google: [REDACTED_IP]",
            "Max: 255.255.255.255" to "Max: [REDACTED_IP]",
            "Not an IP: 256.256.256.256" to "Not an IP: 256.256.256.256"
        )

        cases.forEach { (input, expected) ->
            val result = CloudTextSanitizer.sanitize(input)
            assertEquals(expected, result.text)
        }
    }

    @Test
    fun redactsUrls() {
        val cases = listOf(
            "Visit http://example.com" to "Visit [REDACTED_URL]",
            "Secure https://test.org/path?q=1" to "Secure [REDACTED_URL]",
            "FTP ftp://files.server.net/folder" to "FTP [REDACTED_URL]"
        )

        cases.forEach { (input, expected) ->
            val result = CloudTextSanitizer.sanitize(input)
            assertEquals(expected, result.text)
        }
    }
}
