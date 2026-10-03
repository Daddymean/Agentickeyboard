package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeminiKeyStoreTest {

    private val key = "AIzaSyA-1234567890abcdefghijklmnopqrst"

    @Test
    fun `normalize trims pasted whitespace and newlines`() {
        assertEquals(key, GeminiKeyStore.normalize("  $key\n"))
    }

    @Test
    fun `normalize rejects blank, short, and multi-word input`() {
        assertNull(GeminiKeyStore.normalize("   "))
        assertNull(GeminiKeyStore.normalize("AIza123"))
        assertNull(GeminiKeyStore.normalize("AIzaSyA-1234567890 abcdefghijklmnop"))
    }

    @Test
    fun `mask shows only the first and last four characters`() {
        assertEquals("AIza…qrst", GeminiKeyStore.mask(key))
        assertEquals("••••", GeminiKeyStore.mask("short"))
    }
}
