package io.github.daddymean.agentickeyboard.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method

class GeminiManagerTest {

    // Helper method to access private method using reflection
    private fun invokeStripDraftEcho(draft: String, continuation: String): String {
        // Find GeminiManager class using reflection or load it if the direct reference failed
        val clazz = Class.forName("io.github.daddymean.agentickeyboard.network.GeminiManager")
        val method = clazz.getDeclaredMethod("stripDraftEcho", String::class.java, String::class.java)
        method.isAccessible = true
        // It's a static method conceptually, but Kotlin creates an object. We'll pass the singleton instance.
        val instanceField = clazz.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        val instance = instanceField.get(null)

        return method.invoke(instance, draft, continuation) as String
    }

    @Test
    fun testStripDraftEcho_basicPrefix() {
        assertEquals("world", invokeStripDraftEcho("hello", "hello world"))
    }

    @Test
    fun testStripDraftEcho_caseInsensitive() {
        assertEquals("world", invokeStripDraftEcho("HeLlO", "hello world"))
    }

    @Test
    fun testStripDraftEcho_noPrefixMatch() {
        assertEquals("hi world", invokeStripDraftEcho("hello", "hi world"))
    }

    @Test
    fun testStripDraftEcho_emptyDraft() {
        assertEquals("hello world", invokeStripDraftEcho("", "hello world"))
    }

    @Test
    fun testStripDraftEcho_emptyContinuation() {
        assertEquals("", invokeStripDraftEcho("hello", ""))
    }

    @Test
    fun testStripDraftEcho_continuationIsOnlyDraft() {
        assertEquals("hello", invokeStripDraftEcho("hello", "hello"))
    }

    @Test
    fun testStripDraftEcho_continuationIsOnlyDraftWithWhitespace() {
        assertEquals("hello", invokeStripDraftEcho("hello", " hello   "))
    }

    @Test
    fun testStripDraftEcho_whitespaceHandling() {
        assertEquals("world", invokeStripDraftEcho("  hello  ", "  hello    world  "))
    }

    @Test
    fun testStripDraftEcho_partialWordPrefix() {
        // e.g. draft is "he", continuation is "hello" - should return "llo"
        assertEquals("llo", invokeStripDraftEcho("he", "hello"))
    }
}
