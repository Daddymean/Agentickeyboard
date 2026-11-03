package io.github.daddymean.agentickeyboard.util

import android.text.InputType
import android.view.inputmethod.EditorInfo
import io.github.daddymean.agentickeyboard.util.ToneMirror.Pressure
import io.github.daddymean.agentickeyboard.util.ToneMirror.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMirrorTest {
    private val text = InputType.TYPE_CLASS_TEXT
    private val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD

    @Test fun offByDefaultAndNeverInSensitiveFields() {
        assertFalse(ToneMirror.allowed(false, text, 0))
        assertTrue(ToneMirror.allowed(true, text, 0))
        assertFalse(ToneMirror.allowed(true, password, 0))
        assertFalse(ToneMirror.allowed(true, text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
    }

    @Test fun outgoingTones() {
        assertEquals(Tone.PUSHY, ToneMirror.outgoingHint("I need this ASAP")!!.tone)
        assertEquals(Tone.PUSHY, ToneMirror.outgoingHint("WHERE IS MY ORDER")!!.tone)
        assertEquals(Tone.WARM, ToneMirror.outgoingHint("Thanks for getting back to me")!!.tone)
        assertEquals(Tone.CURT, ToneMirror.outgoingHint("Fine.")!!.tone)
        assertEquals(Tone.NEUTRAL, ToneMirror.outgoingHint("I'll be there around six tonight")!!.tone)
        assertNull(ToneMirror.outgoingHint(" "))
    }

    @Test fun incomingPressurePatterns() {
        assertEquals(listOf(Pressure.FALSE_CHOICE), ToneMirror.pressureFlags("Do you want the 12 month plan or the 24 month one?"))
        assertTrue(Pressure.PRESUPPOSED_YES in ToneMirror.pressureFlags("When you're enjoying your new car this weekend, call me."))
        assertTrue(Pressure.URGENCY in ToneMirror.pressureFlags("Only 2 left, act now"))
        assertTrue(Pressure.EMBEDDED_COMMAND in ToneMirror.pressureFlags("Don't buy it until you've seen everything."))
        assertTrue(Pressure.YES_LADDER in ToneMirror.pressureFlags("You want the best, right? You care about family, don't you?"))
    }

    @Test fun ordinaryMessageHasNoFlags() {
        assertEquals(emptyList<Pressure>(), ToneMirror.pressureFlags("Running 10 min late, see you at the cafe"))
        assertEquals(emptyList<Pressure>(), ToneMirror.pressureFlags(""))
    }
}
