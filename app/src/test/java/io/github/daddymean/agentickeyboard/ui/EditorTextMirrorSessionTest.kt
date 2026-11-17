package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.text.Selection
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import io.github.daddymean.agentickeyboard.util.EditorTextMirror
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * LAB-009: the real keyboard typing into an Android editor (BaseInputConnection's
 * own Editable), wired the way the service wires it: key callbacks edit through a
 * mirror-wrapped connection, and after every edit the test plays the service's
 * onUpdateSelection (one real read, recorded in the mirror). The same session runs
 * with the layout's reads sent to the editor (before) and served by the mirror
 * (after); the editor must end up with exactly the same text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class EditorTextMirrorSessionTest {

    @get:Rule val rule = createComposeRule()

    private val CONTEXT_CHARS = 1000 // AgenticKeyboardService.CONTEXT_CHARS (private)

    /** Counts the synchronous reads that would each be a binder call to the app. */
    private class CountingEditor(base: InputConnection) : InputConnectionWrapper(base, false) {
        var reads = 0
        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? { reads++; return super.getTextBeforeCursor(n, flags) }
        override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? { reads++; return super.getTextAfterCursor(n, flags) }
        override fun getSelectedText(flags: Int): CharSequence? { reads++; return super.getSelectedText(flags) }
    }

    private data class Run(val text: String, val layoutReads: Int, val syncReads: Int, val served: Int, val taps: Int)

    // "see you at teh gamr<⌫>e tomorow night " : spaces drive auto-correct, ⌫ the undo path.
    private val session = "see you at teh gamr<e tomorow night "

    private fun type(serveReads: Boolean): Run {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = BaseInputConnection(View(context), true)
        val editor = CountingEditor(raw)
        val mirror = EditorTextMirror(CONTEXT_CHARS)
        val viewModel = KeyboardViewModel(KeyboardRepository(AppDatabase.getDatabase(context)))
        var syncReads = 0

        // The service's onUpdateSelection -> syncEditorText.
        fun onUpdateSelection() {
            val editable = raw.editable
            val start = Selection.getSelectionStart(editable)
            val end = Selection.getSelectionEnd(editable)
            val before = editor.reads
            val read = editor.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString()
            syncReads += editor.reads - before
            viewModel.setInputText(read.orEmpty())
            if (read != null) mirror.record(read, start, end, editor) else mirror.invalidate()
        }
        fun conn(serve: Boolean) = mirror.wrap(editor, editor, serve)

        rule.setContent {
            MyApplicationTheme {
                ProvideKeyboardMetrics(keyHeightScale = 1f) {
                    AgenticKeyboardLayout(
                        viewModel = viewModel,
                        onKeyPress = { conn(false).commitText(it, 1); onUpdateSelection() },
                        onDelete = { conn(false).deleteSurroundingText(1, 0); onUpdateSelection() },
                        inputConnectionProvider = { conn(serveReads) }
                    )
                }
            }
        }
        rule.waitForIdle()
        onUpdateSelection()
        val layoutBefore = editor.reads - syncReads
        var taps = 0
        for (ch in session) {
            val tag = when (ch) { ' ' -> "key_space"; '<' -> "key_backspace"; else -> "key_$ch" }
            rule.onNodeWithTag(tag).performTouchInput { down(center); up() }
            rule.mainClock.advanceTimeBy(500) // past the double-space window
            rule.waitForIdle()
            taps++
        }
        val layoutReads = editor.reads - syncReads - layoutBefore
        return Run(raw.editable.toString(), layoutReads, syncReads, mirror.served, taps)
    }

    private fun report(label: String, run: Run) = println(
        "LAB-009 typing session $label (${run.taps} taps, Robolectric): editor reads by the layout ${run.layoutReads} " +
            "(served from mirror ${run.served}); service onUpdateSelection reads ${run.syncReads}; " +
            "total ${run.layoutReads + run.syncReads}; final text \"${run.text}\""
    )

    @Test
    fun beforeLayoutReadsGoToTheEditor() {
        val run = type(serveReads = false)
        report("before", run)
        assertEquals(EXPECTED_TEXT, run.text)
    }

    @Test
    fun afterMirrorServesTheLayoutsReadsAndTypesTheSameText() {
        val run = type(serveReads = true)
        report("after", run)
        assertEquals("same text in the editor as without the mirror", EXPECTED_TEXT, run.text)
        assertTrue("layout reads served", run.served > 0)
    }

    private companion object {
        const val EXPECTED_TEXT = "See you at the game tomorrow night "
    }
}
