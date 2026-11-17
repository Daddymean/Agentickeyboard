package io.github.daddymean.agentickeyboard.util

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** LAB-009: when the editor mirror may answer, and that every edit makes it ask again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorTextMirrorTest {

    private class Counting(base: InputConnection) : InputConnectionWrapper(base, false) {
        var reads = 0
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? { reads++; return super.getTextBeforeCursor(n, flags) }
    }

    private val raw = BaseInputConnection(View(ApplicationProvider.getApplicationContext<Context>()), true)
    private val editor = Counting(raw)

    private fun mirrorWith(text: String, window: Int = 1000): EditorTextMirror {
        raw.commitText(text, 1)
        return EditorTextMirror(window).also { it.record(text, text.length, text.length, editor) }
    }

    @Test
    fun servesTheRecordedPrefixWithoutAskingTheEditor() {
        val mirror = mirrorWith("hello world")
        val conn = mirror.wrap(editor, editor, serveReads = true)
        assertEquals("hello world", conn.getTextBeforeCursor(1000, 0).toString())
        assertEquals("world", conn.getTextBeforeCursor(5, 0).toString())
        assertEquals("", conn.getTextBeforeCursor(0, 0).toString())
        assertEquals(0, editor.reads)
    }

    @Test
    fun everyEditMethodMakesTheNextReadGoToTheEditor() {
        val edits: List<Pair<String, (InputConnection) -> Unit>> = listOf(
            "commitText" to { c -> c.commitText("x", 1) },
            "setComposingText" to { c -> c.setComposingText("x", 1) },
            "setComposingRegion" to { c -> c.setComposingRegion(0, 1) },
            "finishComposingText" to { c -> c.finishComposingText() },
            "deleteSurroundingText" to { c -> c.deleteSurroundingText(1, 0) },
            "deleteSurroundingTextInCodePoints" to { c -> c.deleteSurroundingTextInCodePoints(1, 0) },
            "replaceText" to { c -> c.replaceText(0, 1, "y", 1, null) },
            "setSelection" to { c -> c.setSelection(0, 0) },
            "sendKeyEvent" to { c -> c.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)) },
            "performEditorAction" to { c -> c.performEditorAction(0) },
            "performContextMenuAction" to { c -> c.performContextMenuAction(android.R.id.selectAll) },
            "performPrivateCommand" to { c -> c.performPrivateCommand("x", null) },
            "commitCorrection" to { c -> c.commitCorrection(null) },
            "commitCompletion" to { c -> c.commitCompletion(null) },
        )
        for ((name, edit) in edits) {
            val mirror = mirrorWith("abc")
            // An edit through the service's plain (non-serving) connection still invalidates.
            edit(mirror.wrap(editor, editor, serveReads = false))
            val before = editor.reads
            mirror.wrap(editor, editor, serveReads = true).getTextBeforeCursor(10, 0)
            assertEquals("$name must invalidate", before + 1, editor.reads)
        }
    }

    @Test
    fun neverTrustsAnInconsistentOrForeignRead() {
        val other = Counting(raw)
        fun trusted(m: EditorTextMirror, n: Int = 10, flags: Int = 0, from: InputConnection = editor) =
            m.textBefore(n, flags, from)

        val m = EditorTextMirror(1000)
        m.record("abc", 5, 5, editor) // shorter than the caret: editor cut it short
        assertNull(trusted(m))
        m.record("abc", 3, 4, editor) // a selection, not a caret
        assertNull(trusted(m))
        m.record("abc", -1, -1, editor) // unknown caret
        assertNull(trusted(m))
        m.record("abc", 3, 3, null)
        assertNull(trusted(m))
        m.record("abc", 3, 3, editor)
        assertEquals("abc", trusted(m))
        assertNull("another editor", trusted(m, from = other))
        assertNull("styled reads go to the editor", trusted(m, flags = InputConnection.GET_TEXT_WITH_STYLES))
        m.invalidate()
        assertNull(trusted(m))
    }

    @Test
    fun aFullWindowOnlyAnswersReadsItCovers() {
        val m = EditorTextMirror(window = 4)
        m.record("wxyz", 10, 10, editor) // a full window, caret further on
        assertEquals("xyz", m.textBefore(3, 0, editor))
        assertEquals("wxyz", m.textBefore(4, 0, editor))
        assertNull("longer than the window", m.textBefore(5, 0, editor))
        m.record("wxyz", 4, 4, editor) // the whole field
        assertEquals("wxyz", m.textBefore(50, 0, editor))
    }
}
