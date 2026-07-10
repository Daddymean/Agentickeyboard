package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.AppPersonas
import org.junit.Assert.assertEquals
import org.junit.Test

class AppPersonasTest {

    @Test
    fun prefersStoredLabelWhenPresent() {
        assertEquals("Slack", AppPersonas.friendlyName("Slack", "com.Slack"))
        assertEquals("WhatsApp", AppPersonas.friendlyName("WhatsApp", "com.whatsapp"))
    }

    @Test
    fun fallsBackToTidiedPackageSegmentWhenLabelBlank() {
        assertEquals("Whatsapp", AppPersonas.friendlyName("", "com.whatsapp"))
        assertEquals("Messenger", AppPersonas.friendlyName(null, "org.telegram.messenger"))
        assertEquals("Gm", AppPersonas.friendlyName("   ", "com.google.android.gm"))
    }

    @Test
    fun usesWholePackageWhenNoDotOrSegment() {
        assertEquals("Standalone", AppPersonas.friendlyName(null, "standalone"))
    }
}
