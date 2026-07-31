package io.github.daddymean.agentickeyboard.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Method

class GeminiManagerTest {

    private val method: Method = GeminiManager::class.java.getDeclaredMethod(
        "stripDraftEcho",
        String::class.java,
        String::class.java
    ).apply { isAccessible = true }

    private fun stripDraftEcho(draft: String, continuation: String): String {
        return method.invoke(GeminiManager, draft, continuation) as String
    }

    @Test
    fun stripDraftEcho_removesExactPrefix() {
        assertEquals("world", stripDraftEcho("hello", "hello world"))
    }

    @Test
    fun stripDraftEcho_removesCaseInsensitivePrefix() {
        assertEquals("world", stripDraftEcho("HeLlO", "hello world"))
    }

    @Test
    fun stripDraftEcho_trimsWhitespaceFromInputs() {
        assertEquals("world", stripDraftEcho("  hello  ", "  hello world  "))
    }

    @Test
    fun stripDraftEcho_handlesMultipleSpacesAfterPrefix() {
        assertEquals("world", stripDraftEcho("hello", "hello    world"))
    }

    @Test
    fun stripDraftEcho_returnsContinuationWhenNoPrefixMatch() {
        assertEquals("hello world", stripDraftEcho("hi", "hello world"))
    }

    @Test
    fun stripDraftEcho_returnsContinuationWhenDraftIsEmpty() {
        assertEquals("hello world", stripDraftEcho("", "hello world"))
        assertEquals("hello world", stripDraftEcho("   ", "hello world"))
    }

    @Test
    fun stripDraftEcho_returnsContinuationWhenResultWouldBeEmpty() {
        assertEquals("hello", stripDraftEcho("hello", "hello"))
        assertEquals("hello", stripDraftEcho("hello", "hello   "))
    }
}
