package io.github.daddymean.agentickeyboard.util

import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.HandwritingGesture
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.TextAttribute
import java.util.concurrent.Executor
import java.util.function.IntConsumer

/**
 * LAB-009: the text before the caret, as the service last read it in onUpdateSelection.
 *
 * Every space, backspace, swipe and auto-correct asked the editor again with a
 * synchronous getTextBeforeCursor (a binder round trip to the app's UI thread),
 * moments after onUpdateSelection had read exactly that text. The mirror answers
 * those reads instead, but only while it is provably current:
 *
 * - it was recorded from a real read for a known, collapsed caret;
 * - its length matches that caret (the whole prefix), or it is a full [window]
 *   with the caret further on, so a short or odd editor read is never trusted;
 * - nothing has been edited through any connection since (every edit method on a
 *   [wrap]ped connection invalidates it, and the service hands out only wrapped
 *   connections), the field has not changed ([invalidate] on start/finish input),
 *   and the read comes from the same editor connection it was recorded for;
 * - the read is plain text (flags 0) and no longer than what was recorded.
 *
 * Anything else falls through to the editor as before. Reads never change what
 * the editor holds, so a fall-through is only ever slower, never different.
 *
 * Not covered: the app changing the field itself (a mention picker, clearing a sent
 * draft). That moves the caret, so the next onUpdateSelection re-records, but until
 * it is processed the mirror still holds the old text. Callers that size a large
 * delete from a read on a tap read the editor directly (AgenticKeyboardLayout's
 * `currentText(fresh = true)`); per-keystroke reads keep the mirror.
 */
class EditorTextMirror(private val window: Int) {

    private var text: String? = null
    private var owner: InputConnection? = null
    /** The recorded text is everything before the caret, so any length can be answered. */
    private var complete = false

    /** Served from the mirror / sent to the editor; for tests and the latency probe. */
    var served = 0
        private set
    var passedThrough = 0
        private set

    fun invalidate() {
        text = null
        owner = null
    }

    /** After a real read of [textBefore] for the caret onUpdateSelection reported. */
    fun record(textBefore: String, selStart: Int, selEnd: Int, from: InputConnection?) {
        val consistent = selStart >= 0 && selStart == selEnd &&
            (textBefore.length == selStart || (textBefore.length == window && selStart > window))
        if (!consistent || from == null) {
            invalidate()
            return
        }
        text = textBefore
        owner = from
        complete = textBefore.length == selStart
    }

    /** The cached answer to getTextBeforeCursor([n], [flags]) on [from], or null to ask the editor. */
    fun textBefore(n: Int, flags: Int, from: InputConnection): String? {
        val t = text
        if (t == null || owner !== from || flags != 0 || n < 0 || (n > t.length && !complete)) {
            passedThrough++
            return null
        }
        served++
        return if (n >= t.length) t else t.substring(t.length - n)
    }

    /**
     * [base] with every edit invalidating the mirror. [serveReads] also answers
     * getTextBeforeCursor from the mirror; [editor] is the raw connection the
     * mirror was recorded from (the identity check).
     */
    fun wrap(base: InputConnection, editor: InputConnection, serveReads: Boolean): InputConnection =
        Connection(base, editor, serveReads)

    private inner class Connection(
        base: InputConnection,
        private val editor: InputConnection,
        private val serveReads: Boolean
    ) : InputConnectionWrapper(base, false) {

        private inline fun <T> edit(block: () -> T): T {
            invalidate()
            return block()
        }

        override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
            (if (serveReads) textBefore(n, flags, editor) else null) ?: super.getTextBeforeCursor(n, flags)

        // twin: pass-through; the TwinTrackingInputConnection wrapped around this one classifies
        override fun commitText(text: CharSequence?, newCursorPosition: Int) =
            edit { super.commitText(text, newCursorPosition) } // twin: pass-through (classified outside)
        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
            edit { super.commitText(text, newCursorPosition, textAttribute) } // twin: pass-through (classified outside)
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int) =
            edit { super.setComposingText(text, newCursorPosition) }
        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
            edit { super.setComposingText(text, newCursorPosition, textAttribute) }
        override fun setComposingRegion(start: Int, end: Int) = edit { super.setComposingRegion(start, end) }
        override fun setComposingRegion(start: Int, end: Int, textAttribute: TextAttribute?) =
            edit { super.setComposingRegion(start, end, textAttribute) }
        override fun finishComposingText() = edit { super.finishComposingText() }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
            edit { super.deleteSurroundingText(beforeLength, afterLength) }
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
            edit { super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) }
        override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?) =
            edit { super.replaceText(start, end, text, newCursorPosition, textAttribute) }
        override fun commitCompletion(text: CompletionInfo?) = edit { super.commitCompletion(text) }
        override fun commitCorrection(correctionInfo: CorrectionInfo?) = edit { super.commitCorrection(correctionInfo) }
        override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?) =
            edit { super.commitContent(inputContentInfo, flags, opts) }
        override fun setSelection(start: Int, end: Int) = edit { super.setSelection(start, end) }
        override fun sendKeyEvent(event: KeyEvent?) = edit { super.sendKeyEvent(event) }
        override fun performEditorAction(editorAction: Int) = edit { super.performEditorAction(editorAction) }
        override fun performContextMenuAction(id: Int) = edit { super.performContextMenuAction(id) }
        override fun performPrivateCommand(action: String?, data: Bundle?) =
            edit { super.performPrivateCommand(action, data) }
        override fun performHandwritingGesture(gesture: HandwritingGesture, executor: Executor?, consumer: IntConsumer?) =
            edit { super.performHandwritingGesture(gesture, executor, consumer) }
    }
}
