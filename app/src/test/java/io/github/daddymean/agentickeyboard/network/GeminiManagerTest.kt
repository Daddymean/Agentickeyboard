package io.github.daddymean.agentickeyboard.network

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.coroutines.test.runTest

class GeminiManagerTest {

    // Using an instance of GeminiManager to call the now-internal stripDraftEcho.
    // However, since GeminiManager is an object, we can just call it directly.

    @Test
    fun stripDraftEcho_removesExactPrefix() {
        val result = GeminiManager.stripDraftEcho("Hello", "Hello World")
        assertEquals("World", result)
    }

    @Test
    fun stripDraftEcho_caseInsensitive() {
        val result = GeminiManager.stripDraftEcho("hello", "Hello World")
        assertEquals("World", result)
    }

    @Test
    fun stripDraftEcho_noEcho_returnsContinuation() {
        val result = GeminiManager.stripDraftEcho("Hi", "Hello World")
        assertEquals("Hello World", result)
    }

    @Test
    fun stripDraftEcho_emptyDraft_returnsContinuation() {
        val result = GeminiManager.stripDraftEcho("", "Hello World")
        assertEquals("Hello World", result)
    }

    @Test
    fun stripDraftEcho_trimsContinuation() {
        val result = GeminiManager.stripDraftEcho("Hello", "  Hello World  ")
        assertEquals("World", result)
    }

    @Test
    fun stripDraftEcho_trimsDraft() {
        val result = GeminiManager.stripDraftEcho("  Hello  ", "Hello World")
        assertEquals("World", result)
    }

    @Test
    fun stripDraftEcho_draftLongerThanContinuation() {
        val result = GeminiManager.stripDraftEcho("Hello World", "Hello")
        assertEquals("Hello", result)
    }

    @Test
    fun stripDraftEcho_returnsOriginalIfTrimmedResultIsEmpty() {
        // If the continuation is exactly the draft, it strips it and returns empty string.
        // Wait, the code says: return c.substring(d.length).trimStart().ifEmpty { c }
        // So if continuation is exactly draft, c.substring(d.length) is "", ifEmpty returns c.
        val result = GeminiManager.stripDraftEcho("Hello", "Hello")
        assertEquals("Hello", result)
    }

    @Test
    fun stripDraftEcho_handlesWhitespaceOnlyContinuation() {
        val result = GeminiManager.stripDraftEcho("Hello", "   ")
        assertEquals("", result)
    }
}
