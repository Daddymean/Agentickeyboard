package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppPersonasTest {

    @Test
    fun `friendlyName returns trimmed appLabel if present and not empty`() {
        assertEquals("WhatsApp", AppPersonas.friendlyName(" WhatsApp ", "com.whatsapp"))
        assertEquals("WhatsApp", AppPersonas.friendlyName("WhatsApp", "com.whatsapp"))
    }

    @Test
    fun `friendlyName uses package name last segment if appLabel is null or blank`() {
        assertEquals("Whatsapp", AppPersonas.friendlyName(null, "com.whatsapp"))
        assertEquals("Whatsapp", AppPersonas.friendlyName("   ", "com.whatsapp"))
        assertEquals("Messaging", AppPersonas.friendlyName(null, "com.google.android.apps.messaging"))
    }

    @Test
    fun `friendlyName capitalizes segment properly`() {
        assertEquals("Discord", AppPersonas.friendlyName(null, "com.discord"))
        assertEquals("X", AppPersonas.friendlyName(null, "com.twitter.x"))
    }

    @Test
    fun `friendlyName uses entire package name if no dots exist`() {
        assertEquals("Mycoolapp", AppPersonas.friendlyName(null, "mycoolapp"))
        assertEquals("Test", AppPersonas.friendlyName(null, "test"))
    }

    @Test
    fun `friendlyName handles package name with trailing dot gracefully`() {
        // substringAfterLast('.') on "com.app." returns ""
        // ifBlank falls back to "com.app."
        // capitalized becomes "Com.app."
        assertEquals("Com.app.", AppPersonas.friendlyName(null, "com.app."))
    }

    @Test
    fun `friendlyName handles empty package name`() {
        assertEquals("", AppPersonas.friendlyName(null, ""))
    }
}
