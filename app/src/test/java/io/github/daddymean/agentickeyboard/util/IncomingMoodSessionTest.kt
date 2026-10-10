package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** KEYBOARD-022: gates, expiry, and that no message text is kept. */
class IncomingMoodSessionTest {

    @Test
    fun everyGateMustPass() {
        assertTrue(IncomingMoodSession.shouldRead(true, true, true, false, true))
        assertFalse("switch off", IncomingMoodSession.shouldRead(false, true, true, false, true))
        assertFalse("app not allowed", IncomingMoodSession.shouldRead(true, false, true, false, true))
        assertFalse("service off", IncomingMoodSession.shouldRead(true, true, false, false, true))
        assertFalse("sensitive field", IncomingMoodSession.shouldRead(true, true, true, true, true))
        assertFalse("keyboard hidden", IncomingMoodSession.shouldRead(true, true, true, false, false))
    }

    @Test
    fun keepsOnlyTheMoodAndExpires() {
        val session = IncomingMoodSession()
        val badge = session.update("I'm so angry right now", now = 1_000, ttlMs = 60_000)!!
        assertEquals(IncomingSentiment.Mood.TENSE, badge.mood)
        assertEquals(badge, session.current(60_999))
        assertNull(session.current(61_000))
        assertNull(session.badge)
    }

    @Test
    fun clearAndEmptyInputLeaveNothing() {
        val session = IncomingMoodSession()
        session.update("thanks!", 0)
        session.clear()
        assertNull(session.current(1))
        assertNull(session.update(null, 0))
        assertNull(session.update("  ", 0))
    }

    @Test
    fun badgeHasNoFieldThatCouldHoldMessageText() {
        val fields = IncomingMoodSession.Badge::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name to it.type }
        assertEquals(listOf("mood" to IncomingSentiment.Mood::class.java, "expiresAt" to Long::class.javaPrimitiveType),
            fields)
        val sessionFields = IncomingMoodSession::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
        assertTrue(sessionFields.none { it.type == String::class.java || it.type == CharSequence::class.java })
    }
}
