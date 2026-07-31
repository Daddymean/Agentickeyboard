package io.github.daddymean.agentickeyboard.network

import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiManagerTest {

    @Test
    fun stripDraftEcho_removesExactPrefix() {
        val draft = "Hello "
        val continuation = "Hello world"
        assertEquals("world", GeminiManager.stripDraftEcho(draft, continuation))
    }

    @Test
    fun stripDraftEcho_handlesCaseInsensitivePrefix() {
        val draft = "hello"
        val continuation = "HELLO WORLD"
        assertEquals("WORLD", GeminiManager.stripDraftEcho(draft, continuation))
    }

    @Test
    fun stripDraftEcho_ignoresWhitespacesAtStartAndEnd() {
        val draft = " hello "
        val continuation = "   hello world  "
        assertEquals("world", GeminiManager.stripDraftEcho(draft, continuation))
    }

    @Test
    fun stripDraftEcho_returnsContinuationIfNoPrefixMatch() {
        val draft = "Goodbye"
        val continuation = "Hello world"
        assertEquals("Hello world", GeminiManager.stripDraftEcho(draft, continuation))
    }

    @Test
    fun stripDraftEcho_returnsContinuationIfDraftEmpty() {
        val draft = ""
        val continuation = "Hello world"
        assertEquals("Hello world", GeminiManager.stripDraftEcho(draft, continuation))
    }

    @Test
    fun stripDraftEcho_returnsContinuationIfContinuationIsJustDraft() {
        val draft = "Hello"
        val continuation = "Hello"
        assertEquals("Hello", GeminiManager.stripDraftEcho(draft, continuation))
    }

}
