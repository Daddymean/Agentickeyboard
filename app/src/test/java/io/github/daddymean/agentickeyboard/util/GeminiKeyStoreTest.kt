package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.KeyStore
import javax.crypto.KeyGenerator

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

    @Test
    fun `removal deletes the wrapping Keystore entry`() {
        // A JVM PKCS12 store stands in for AndroidKeyStore, which Robolectric lacks.
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        val aes = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        store.setEntry(
            GeminiKeyStore.KEY_ALIAS,
            KeyStore.SecretKeyEntry(aes),
            KeyStore.PasswordProtection(CharArray(0))
        )
        assertTrue(store.containsAlias(GeminiKeyStore.KEY_ALIAS))

        assertTrue(GeminiKeyStore.deleteKeystoreEntry(store))
        assertFalse(store.containsAlias(GeminiKeyStore.KEY_ALIAS))
    }

    @Test
    fun `removal is a no-op when no Keystore entry exists`() {
        val store = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        assertTrue(GeminiKeyStore.deleteKeystoreEntry(store))
    }
}
