package io.github.daddymean.agentickeyboard.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResponseCacheTest {

    @Test
    fun keysAreOpaqueSha256Digests() {
        val sensitiveText = "Email buyer@example.com about order 123456789"
        val key = AiCacheKeys.summary("gemini-test", "Professional", sensitiveText)

        assertEquals(64, key.digest.length)
        assertTrue(key.digest.all { it.isDigit() || it in 'a'..'f' })
        assertFalse(key.digest.contains("buyer@example.com"))
        assertFalse(key.digest.contains("123456789"))
    }

    @Test
    fun summaryKeyIncludesPersonalizationContext() {
        val text = "The same long message should not cross persona boundaries in cache."

        val professional = AiCacheKeys.summary("gemini-test", "Professional", text)
        val joyful = AiCacheKeys.summary("gemini-test", "Joyful", text)

        assertNotEquals(professional, joyful)
    }

    @Test
    fun translationKeyIncludesLanguagesAndPersonalizationContext() {
        val text = "Please confirm the meeting time."
        val base = AiCacheKeys.translation("gemini-test", "English", "Spanish", "Professional", text)

        assertNotEquals(
            base,
            AiCacheKeys.translation("gemini-test", "English", "French", "Professional", text)
        )
        assertNotEquals(
            base,
            AiCacheKeys.translation("gemini-test", "English", "Spanish", "Casual", text)
        )
    }

    @Test
    fun entriesExpireAfterTtl() {
        var now = 1_000L
        val cache = AiResponseCache<String>(
            maxEntries = 2,
            ttlMillis = 100L,
            clockMillis = { now }
        )
        val key = AiCacheKeys.explanation("gemini-test", "private draft")

        cache.put(key, "cached response")
        assertEquals("cached response", cache.get(key))

        now = 1_100L
        assertNull(cache.get(key))
        assertEquals(0, cache.size())
    }

    @Test
    fun leastRecentlyUsedEntryIsEvicted() {
        val cache = AiResponseCache<String>(maxEntries = 2, ttlMillis = 10_000L)
        val first = AiCacheKeys.explanation("gemini-test", "first")
        val second = AiCacheKeys.explanation("gemini-test", "second")
        val third = AiCacheKeys.explanation("gemini-test", "third")

        cache.put(first, "one")
        cache.put(second, "two")
        assertEquals("one", cache.get(first))
        cache.put(third, "three")

        assertEquals("one", cache.get(first))
        assertNull(cache.get(second))
        assertEquals("three", cache.get(third))
        assertEquals(2, cache.size())
    }
}
