package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnDeviceAiTest {

    @Test
    fun toneFor_mapsExpectedKeywordsToTones() {
        // SHORTEN
        assertEquals(OnDeviceTone.SHORTEN, OnDeviceAi.toneFor("make it shorter"))
        assertEquals(OnDeviceTone.SHORTEN, OnDeviceAi.toneFor("shorten this"))
        assertEquals(OnDeviceTone.SHORTEN, OnDeviceAi.toneFor("tighter please"))
        assertEquals(OnDeviceTone.SHORTEN, OnDeviceAi.toneFor("be concise"))

        // ELABORATE
        assertEquals(OnDeviceTone.ELABORATE, OnDeviceAi.toneFor("make it longer"))
        assertEquals(OnDeviceTone.ELABORATE, OnDeviceAi.toneFor("expand on this"))
        assertEquals(OnDeviceTone.ELABORATE, OnDeviceAi.toneFor("give me more detail"))
        assertEquals(OnDeviceTone.ELABORATE, OnDeviceAi.toneFor("elaborate please"))

        // PROFESSIONAL
        assertEquals(OnDeviceTone.PROFESSIONAL, OnDeviceAi.toneFor("make it formal"))
        assertEquals(OnDeviceTone.PROFESSIONAL, OnDeviceAi.toneFor("sound professional"))

        // FRIENDLY
        assertEquals(OnDeviceTone.FRIENDLY, OnDeviceAi.toneFor("warmer tone"))
        assertEquals(OnDeviceTone.FRIENDLY, OnDeviceAi.toneFor("more friendly"))
        assertEquals(OnDeviceTone.FRIENDLY, OnDeviceAi.toneFor("joyful response"))
        assertEquals(OnDeviceTone.FRIENDLY, OnDeviceAi.toneFor("keep it casual"))
        assertEquals(OnDeviceTone.FRIENDLY, OnDeviceAi.toneFor("be empathetic"))
    }

    @Test
    fun toneFor_returnsNullForUnmatchedInstructions() {
        assertNull(OnDeviceAi.toneFor("firmer"))
        assertNull(OnDeviceAi.toneFor("make it sound angry"))
        assertNull(OnDeviceAi.toneFor("just rewrite it"))
        assertNull(OnDeviceAi.toneFor(""))
        assertNull(OnDeviceAi.toneFor("   "))
    }

    @Test
    fun toneFor_isCaseInsensitive() {
        assertEquals(OnDeviceTone.SHORTEN, OnDeviceAi.toneFor("SHORTER"))
        assertEquals(OnDeviceTone.ELABORATE, OnDeviceAi.toneFor("More Detail"))
        assertEquals(OnDeviceTone.PROFESSIONAL, OnDeviceAi.toneFor("FORMAL"))
        assertEquals(OnDeviceTone.FRIENDLY, OnDeviceAi.toneFor("FriendlY"))
    }
}
