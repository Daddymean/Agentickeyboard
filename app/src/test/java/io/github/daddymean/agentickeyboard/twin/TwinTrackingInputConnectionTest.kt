package io.github.daddymean.agentickeyboard.twin

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        val session = TwinCaptureSession().apply { start("com.whatsapp", "", TwinTyping.OPEN, chatField = true) }
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
        val session = TwinCaptureSession().apply { start("com.whatsapp", "", TwinTyping.OPEN, chatField = true) }
        var now = 1_000L
        val conn = TwinTrackingInputConnection(base, session) { now }
        now += 40; conn.commitText("ab", 1); session.onText("ab", now)
        conn.performContextMenuAction(android.R.id.paste)
        now += 40; session.onText("ab pasted", now)
        assertNull(session.onSend("ab pasted", now))

        val cutSession = TwinCaptureSession().apply { start("com.whatsapp", "", TwinTyping.OPEN, chatField = true) }
        val cutConn = TwinTrackingInputConnection(base, cutSession) { now }
        now += 40; cutConn.commitText("draft", 1); cutSession.onText("draft", now)
        cutConn.performContextMenuAction(android.R.id.cut)
        now += 2_000
        assertNull(cutSession.onText("", now))

        cutConn.deleteSurroundingText(1, 0)
        cutConn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        assertEquals(listOf("commitText", "performContextMenuAction", "commitText", "performContextMenuAction", "deleteSurroundingText", "sendKeyEvent"), calls)
    }
}
