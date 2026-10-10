package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** KEYBOARD-021: the exception contributes only its class name to a log line. */
class SafeLogTest {
    private val secret = "my password is hunter2 and the reply was ok"

    @Test
    fun noErrorKeepsTheStaticMessage() {
        assertEquals("Error in fixGrammar", SafeLog.format("Error in fixGrammar", null))
    }

    @Test
    fun errorAddsOnlyItsClassName() {
        val line = SafeLog.format("Error in fixGrammar", IllegalStateException(secret))
        assertEquals("Error in fixGrammar [IllegalStateException]", line)
        assertFalse(line.contains("hunter2"))
    }

    @Test
    fun causeChainAndSuppressedTextNeverAppear() {
        val error = RuntimeException("wrapper: $secret", java.io.IOException("body: $secret"))
        error.addSuppressed(Exception("suppressed: $secret"))
        val line = SafeLog.format("Clipboard history write failed", error)
        assertEquals("Clipboard history write failed [RuntimeException]", line)
        assertFalse(line.contains("hunter2"))
        assertFalse(line.contains("IOException"))
    }

    @Test
    fun anonymousExceptionFallsBackToThrowable() {
        val anonymous = object : Exception(secret) {}
        assertEquals("x [Throwable]", SafeLog.format("x", anonymous))
    }
}
