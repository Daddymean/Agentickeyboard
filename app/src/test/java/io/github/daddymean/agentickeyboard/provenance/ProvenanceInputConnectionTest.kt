package io.github.daddymean.agentickeyboard.provenance

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import io.github.daddymean.agentickeyboard.twin.TwinCaptureSession
import io.github.daddymean.agentickeyboard.twin.TwinTrackingInputConnection
import io.github.daddymean.agentickeyboard.twin.TwinTyping
import io.github.daddymean.agentickeyboard.util.commitInserted
import io.github.daddymean.agentickeyboard.util.commitTyped
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProvenanceInputConnectionTest {

    private val calls = ArrayList<String>()
    private var accept = true

    private val editor = Proxy.newProxyInstance(
        InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
    ) { _, method, _ ->
        calls += method.name
        when (method.returnType) {
            java.lang.Boolean.TYPE -> accept
            Integer.TYPE -> 0
            else -> null
        }
    } as InputConnection

    private val ledger = OriginLedger().apply { begin(0, 0, 0L) }
    private var now = 0L
    private val conn = ProvenanceInputConnection(editor, ledger) { now }

    private fun nonZero() = ledger.counts().filterValues { it > 0 }

    @Test
    fun keysSuggestionsAndInsertsAreClassifiedAndReachTheEditor() {
        for (c in "hi ") { now += 120; conn.commitText(c.toString(), 1) }
        conn.commitTyped("there ", 1) // a suggestion pick / auto-fix
        conn.commitInserted("Sent from my phone", 1)
        assertEquals("hi there Sent from my phone", ledger.textSnapshot())
        assertEquals(mapOf(Origin.TYPED to 3, Origin.ASSISTED to 6, Origin.INSERTED to 18), nonZero())
        assertEquals(5, calls.count { it == "commitText" })
    }

    @Test
    fun aMultiCharacterCommitTextIsNeverTyping() {
        conn.commitText("pasted words", 1)
        assertEquals(mapOf(Origin.INSERTED to 12), nonZero())
    }

    @Test
    fun labelledPathsOverrideTheDefault() {
        conn.withOrigin(Origin.SWIPED) { conn.commitTyped("hello", 1) }
        (conn as InputConnection).withOrigin(Origin.AI) { conn.commitText(" world", 1) }
        assertEquals(mapOf(Origin.SWIPED to 5, Origin.AI to 6), nonZero())
    }

    @Test
    fun deletesAndKeysAreApplied() {
        for (c in "abcd") conn.commitText(c.toString(), 1)
        conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
        conn.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)) // the up event deletes nothing
        conn.deleteSurroundingText(1, 0)
        conn.setSelection(0, 0)
        conn.deleteSurroundingTextInCodePoints(0, 1)
        assertEquals("b", ledger.textSnapshot())
    }

    @Test
    fun aRefusedEditIsNotRecorded() {
        accept = false
        conn.commitText("a", 1)
        conn.deleteSurroundingText(1, 0)
        assertEquals(0, ledger.length)
    }

    @Test
    fun editorPasteLeavesAGapForTheResyncToFill() {
        for (c in "ab") conn.commitText(c.toString(), 1)
        conn.setSelection(1, 1)
        conn.performContextMenuAction(android.R.id.paste)
        ledger.select(3, 3) // onUpdateSelection after the editor pasted "XY"
        ledger.resyncBefore("aXY")
        ledger.reconcile("aXYb")
        assertEquals(mapOf(Origin.TYPED to 2, Origin.UNWITNESSED to 2), nonZero())
    }

    @Test
    fun wrappingTheTwinConnectionKeepsBothRecordsAndCommitsOnce() {
        val session = TwinCaptureSession().apply {
            start("com.whatsapp", "", TwinTyping.OPEN, chatField = true, textAfter = "", hasSelection = false)
        }
        val stack = ProvenanceInputConnection(TwinTrackingInputConnection(editor, session), ledger) { now }
        stack.commitTyped("swiped", 1)
        stack.commitInserted(" ai", 1)
        assertEquals(2, calls.count { it == "commitText" })
        assertEquals(mapOf(Origin.ASSISTED to 6, Origin.INSERTED to 3), nonZero())
    }

    @Test
    fun anInactiveLedgerIgnoresEverything() {
        ledger.reset()
        conn.commitText("a", 1)
        assertEquals(0, ledger.length)
        assertEquals(1, calls.count { it == "commitText" })
    }
}
