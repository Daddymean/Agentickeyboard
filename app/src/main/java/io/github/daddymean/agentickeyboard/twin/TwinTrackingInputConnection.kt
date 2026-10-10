package io.github.daddymean.agentickeyboard.twin

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute

/**
 * KEYBOARD-024: the keyboard's own edits, reported to [TwinCaptureSession] as they are
 * made, so the twin can tell typing from caret movement and from text that came from
 * elsewhere. Every edit still goes to the editor unchanged.
 *
 * Commits are typing (keys, swipe words, suggestions). Deletes are the user's own.
 * Pastes and rich content are inserts, which taint the draft. Cut and select-all
 * mean a following clear is a deletion, not a send. Callers that insert
 * non-typed text through this connection (AI apply, snippets, clipboard) must also
 * call [TwinCaptureSession.markInserted].
 */
class TwinTrackingInputConnection(
    base: InputConnection,
    private val session: TwinCaptureSession,
    private val clock: () -> Long = System::currentTimeMillis
) : InputConnectionWrapper(base, false) {

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        text?.let { session.noteKeyboardCommit(it, clock()) }
        return super.commitText(text, newCursorPosition)
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        session.noteKeyboardCommit(text, clock())
        return super.commitText(text, newCursorPosition, textAttribute)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        text?.let { session.noteKeyboardCommit(it, clock()) }
        return super.setComposingText(text, newCursorPosition)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        session.noteKeyboardDelete(clock(), beforeLength.coerceAtLeast(0) + 1)
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        session.noteKeyboardDelete(clock(), beforeLength.coerceAtLeast(0) * 2)
        return super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_DEL || event?.keyCode == KeyEvent.KEYCODE_FORWARD_DEL) {
            session.noteKeyboardDelete(clock(), 2)
        }
        return super.sendKeyEvent(event)
    }

    override fun performContextMenuAction(id: Int): Boolean {
        when (id) {
            android.R.id.paste, android.R.id.pasteAsPlainText -> session.markInserted(clock())
            android.R.id.cut, android.R.id.selectAll -> session.markOwnDelete(clock())
        }
        return super.performContextMenuAction(id)
    }

    override fun commitContent(
        inputContentInfo: android.view.inputmethod.InputContentInfo,
        flags: Int,
        opts: android.os.Bundle?
    ): Boolean {
        session.markInserted(clock())
        return super.commitContent(inputContentInfo, flags, opts)
    }
}
