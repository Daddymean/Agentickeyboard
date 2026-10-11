package io.github.daddymean.agentickeyboard.twin

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import io.github.daddymean.agentickeyboard.util.TypingAwareConnection

/**
 * KEYBOARD-024: the keyboard's own edits, reported to [TwinCaptureSession] as they are
 * made, so the twin can tell typing from caret movement and from text that came from
 * elsewhere. Every edit still goes to the editor unchanged.
 *
 * **Only single key events are typing by default.** A plain commit of one code point
 * is a typed key; any longer commit is an insertion and taints the draft, so a new
 * multi-character path is safe by default (KEYBOARD-024 review r4238828510). Typed
 * multi-character commits (swipe word, auto-fix of the typed word, double-space
 * period) must use [commitTypedText]; non-typed text should use [commitInsertedText].
 * Deletes are the user's own. Pastes and rich content are inserts. Cut and
 * select-all mean a following clear is a deletion, not a send.
 */
class TwinTrackingInputConnection(
    base: InputConnection,
    private val session: TwinCaptureSession,
    private val clock: () -> Long = System::currentTimeMillis
) : InputConnectionWrapper(base, false), TypingAwareConnection {

    /**
     * A single key is credited as typing only after the editor accepted it (Codex review
     * on 08efa93, r4239970466); anything else is an insertion whether or not it landed.
     */
    private fun report(text: CharSequence, accepted: Boolean) {
        if (!isSingleKey(text)) session.markInserted(clock())
        else if (accepted) session.noteKeyboardCommit(text, clock())
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val ok = super.commitText(text, newCursorPosition)
        text?.let { report(it, ok) }
        return ok
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        val ok = super.commitText(text, newCursorPosition, textAttribute)
        report(text, ok)
        return ok
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val ok = super.setComposingText(text, newCursorPosition)
        text?.let { report(it, ok) }
        return ok
    }

    override fun commitTypedText(text: CharSequence, newCursorPosition: Int): Boolean {
        val ok = super.commitText(text, newCursorPosition)
        if (ok) session.noteKeyboardCommit(text, clock())
        return ok
    }

    override fun commitInsertedText(text: CharSequence, newCursorPosition: Int): Boolean {
        session.markInserted(clock())
        return super.commitText(text, newCursorPosition)
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

    companion object {
        /** One key: exactly one code point (a letter, digit, punctuation mark or symbol). */
        fun isSingleKey(text: CharSequence): Boolean =
            text.isNotEmpty() && Character.codePointCount(text, 0, text.length) == 1
    }
}
