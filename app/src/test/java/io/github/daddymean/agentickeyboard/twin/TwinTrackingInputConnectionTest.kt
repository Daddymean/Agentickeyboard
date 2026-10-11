package io.github.daddymean.agentickeyboard.twin

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwinTrackingInputConnectionTest {
    private val calls = ArrayList<String>()
    private val base = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
    ) { _, method, _ ->
        calls += method.name
        when (method.returnType) {
            java.lang.Boolean.TYPE -> true
            Integer.TYPE -> 0
            else -> null
        }
    } as InputConnection

    @Test
    fun keyboardCommitsCountAsTypingAndReachTheEditor() {
        val session = TwinCaptureSession().apply { start("com.whatsapp", "", TwinTyping.OPEN, chatField = true, textAfter = "", hasSelection = false) }
        var now = 1_000L
        val conn = TwinTrackingInputConnection(base, session) { now }
        var field = ""
        for (ch in "hi there") {
            now += 40
            conn.commitText(ch.toString(), 1)
            field += ch
            session.onText(field, now)
        }
        assertEquals(8, calls.count { it == "commitText" })
        now += 40
        assertEquals("hi there", session.onSend(field, now)?.text)
    }

    @Test
    fun pasteCutAndDeleteAreReported() {
        val session = TwinCaptureSession().apply { start("com.whatsapp", "", TwinTyping.OPEN, chatField = true, textAfter = "", hasSelection = false) }
        var now = 1_000L
        val conn = TwinTrackingInputConnection(base, session) { now }
        for (ch in "ab") { now += 40; conn.commitText(ch.toString(), 1) }
        session.onText("ab", now)
        conn.performContextMenuAction(android.R.id.paste)
        now += 40; session.onText("ab pasted", now)
        assertNull(session.onSend("ab pasted", now))

        val cutSession = TwinCaptureSession().apply { start("com.whatsapp", "", TwinTyping.OPEN, chatField = true, textAfter = "", hasSelection = false) }
        val cutConn = TwinTrackingInputConnection(base, cutSession) { now }
        now += 40; cutConn.commitTypedText("draft", 1); cutSession.onText("draft", now)
        cutConn.performContextMenuAction(android.R.id.cut)
        now += 2_000
        assertNull(cutSession.onText("", now))

        cutConn.deleteSurroundingText(1, 0)
        cutConn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals(listOf("commitText", "commitText", "performContextMenuAction", "commitText", "performContextMenuAction", "deleteSurroundingText", "sendKeyEvent"), calls)
    }

    @Test
    fun multiCharacterCommitsAreInsertionsUnlessDeclaredTyped() {
        // KEYBOARD-024 r4238828510: only a single key is typing by default.
        for (payload in listOf("ab", "Pasted text from elsewhere", "\uD83D\uDE00\uD83D\uDE00", "ok ")) {
            val session = TwinCaptureSession().apply { start("com.whatsapp", "", TwinTyping.OPEN, chatField = true, textAfter = "", hasSelection = false) }
            var now = 1_000L
            val conn = TwinTrackingInputConnection(base, session) { now }
            conn.commitText(payload, 1)
            now += 5; session.onText(payload, now)
            assertNull(payload, session.onSend(payload, now + 40))
        }
        // A single code point, even a surrogate pair, is one key.
        assertTrue(TwinTrackingInputConnection.isSingleKey("\uD83D\uDE00"))
        assertTrue(TwinTrackingInputConnection.isSingleKey("a"))
        assertFalse(TwinTrackingInputConnection.isSingleKey("ab"))
        assertFalse(TwinTrackingInputConnection.isSingleKey(""))

        // Composing text follows the same rule.
        val session = TwinCaptureSession().apply { start("com.whatsapp", "", TwinTyping.OPEN, chatField = true, textAfter = "", hasSelection = false) }
        var now = 1_000L
        val conn = TwinTrackingInputConnection(base, session) { now }
        conn.setComposingText("hello there", 1)
        now += 5; session.onText("hello there", now)
        assertNull(session.onSend("hello there", now + 40))

        // The explicit typed path (swipe word) still learns; the explicit inserted path never does.
        val typed = TwinCaptureSession().apply { start("com.whatsapp", "", TwinTyping.OPEN, chatField = true, textAfter = "", hasSelection = false) }
        val typedConn = TwinTrackingInputConnection(base, typed) { now }
        typedConn.commitTypedText("hello", 1); typed.onText("hello", now + 5)
        assertEquals("hello", typed.onSend("hello", now + 40)?.text)
        val ins = TwinCaptureSession().apply { start("com.whatsapp", "", TwinTyping.OPEN, chatField = true, textAfter = "", hasSelection = false) }
        val insConn = TwinTrackingInputConnection(base, ins) { now }
        insConn.commitInsertedText("h", 1); ins.onText("h", now + 5)
        assertNull(ins.onSend("h", now + 40))
    }
}
