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
}
