package io.github.daddymean.agentickeyboard.util

import android.app.Activity
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CaretCommitTest {
    private fun editor(text: String, start: Int, end: Int = start): Pair<EditText, InputConnection> {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = EditText(activity)
        view.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        activity.setContentView(view)
        view.setText(text)
        view.requestFocus()
        view.setSelection(start, end)
        return view to requireNotNull(view.onCreateInputConnection(EditorInfo()))
    }

    @Test fun `caret and undo preserve surrounding text and replace selections`() {
        for (offset in listOf(0, 3, 11)) {
            val (view, connection) = editor("prefix OLD suffix", 7, 10)
            val replacement = "Hi , thanks"
            connection.commitTextWithCaret(replacement, offset)
            assertEquals("prefix Hi , thanks suffix", view.text.toString())
            assertEquals(7 + offset, view.selectionStart)
            val undo = requireNotNull(connection.captureCommittedEditUndo("OLD", replacement, offset))
            assertTrue(undo.undoIfUnchanged(connection))
            assertEquals("prefix OLD suffix", view.text.toString())
            assertEquals(10, view.selectionStart)
        }
    }

    @Test fun `smart space undo removes suffix after internal caret`() {
        val (view, connection) = editor("prefix ab suffix", 9)
        connection.deleteSurroundingText(2, 0)
        connection.commitTextWithCaret("Hi , thanks ", 3)
        val undo = requireNotNull(connection.captureCommittedEditUndo("ab ", "Hi , thanks ", 3))
        assertTrue(undo.undoIfUnchanged(connection))
        assertEquals("prefix ab  suffix", view.text.toString())
    }

    @Test fun `moved caret or edited suffix refuses undo without changing text`() {
        val (view, connection) = editor("", 0)
        connection.commitTextWithCaret("abcXYZ", 3)
        val undo = requireNotNull(connection.captureCommittedEditUndo("old", "abcXYZ", 3))
        view.setSelection(2)
        assertFalse(undo.undoIfUnchanged(connection))
        assertEquals("abcXYZ", view.text.toString())
        view.setSelection(3)
        view.text.replace(3, 6, "NEW")
        view.setSelection(3)
        assertFalse(undo.undoIfUnchanged(connection))
        assertEquals("abcNEW", view.text.toString())
    }

    @Test fun `emoji offsets are UTF16 and empty or invalid markers are safe`() {
        val (view, connection) = editor("", 0)
        connection.commitTextWithCaret("😀suffix", 2)
        assertEquals(2, view.selectionStart)
        val undo = requireNotNull(connection.captureCommittedEditUndo("original", "😀suffix", 2))
        assertTrue(undo.undoIfUnchanged(connection))
        assertEquals("original", view.text.toString())
        for (offset in listOf<Int?>(null, -1, 100)) {
            view.setText("")
            view.setSelection(0)
            connection.commitTextWithCaret("abc", offset)
            assertEquals("abc", view.text.toString())
            assertEquals(3, view.selectionStart)
        }
        connection.commitTextWithCaret("", 0)
        assertEquals("abc", view.text.toString())
    }
}
