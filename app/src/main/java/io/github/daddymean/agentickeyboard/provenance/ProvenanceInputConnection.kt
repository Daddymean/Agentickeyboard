package io.github.daddymean.agentickeyboard.provenance

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.TextAttribute
import io.github.daddymean.agentickeyboard.util.TypingAwareConnection

/**
 * Proof of Thought slice 1: the outermost wrapper on every connection the service
 * hands out while the [ledger] is active. Each edit reaches the editor unchanged
 * first; only an edit the editor accepted is applied to the ledger.
 *
 * Classification follows KEYBOARD-024's safe default: one code point through
 * commitText is a [Origin.TYPED] key, [commitTypedText] is [Origin.ASSISTED] (a
 * keyboard-shaped word the user chose), and any other text is [Origin.INSERTED].
 * Known paths sharpen this with [OriginLedger.labelled]. Edits this class does not
 * model (paste menu, rich content, composing text, replaceText) are left to the
 * ledger's resync, which tags whatever they produced [Origin.UNWITNESSED].
 */
class ProvenanceInputConnection(
    base: InputConnection,
    private val ledger: OriginLedger,
    private val clock: () -> Long = System::currentTimeMillis
) : InputConnectionWrapper(base, false), TypingAwareConnection, OriginAwareConnection {

    private val typingAwareBase = base as? TypingAwareConnection

    private fun record(text: CharSequence, default: Origin, newCursorPosition: Int) {
        ledger.commit(text, ledger.originFor(default), newCursorPosition, clock())
    }

    private fun keyOrInserted(text: CharSequence): Origin =
        if (text.isNotEmpty() && Character.codePointCount(text, 0, text.length) == 1) Origin.TYPED else Origin.INSERTED

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val ok = super.commitText(text, newCursorPosition)
        if (ok && text != null) record(text, keyOrInserted(text), newCursorPosition)
        return ok
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean {
        val ok = super.commitText(text, newCursorPosition, textAttribute)
        if (ok) record(text, keyOrInserted(text), newCursorPosition)
        return ok
    }

    override fun commitTypedText(text: CharSequence, newCursorPosition: Int): Boolean {
        val ok = typingAwareBase?.commitTypedText(text, newCursorPosition) ?: super.commitText(text, newCursorPosition)
        if (ok) record(text, Origin.ASSISTED, newCursorPosition)
        return ok
    }

    override fun commitInsertedText(text: CharSequence, newCursorPosition: Int): Boolean {
        val ok = typingAwareBase?.commitInsertedText(text, newCursorPosition) ?: super.commitText(text, newCursorPosition)
        if (ok) record(text, Origin.INSERTED, newCursorPosition)
        return ok
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val ok = super.deleteSurroundingText(beforeLength, afterLength)
        if (ok) ledger.deleteSurrounding(beforeLength, afterLength)
        return ok
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val ok = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        if (ok) ledger.deleteSurroundingInCodePoints(beforeLength, afterLength)
        return ok
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        val ok = super.sendKeyEvent(event)
        if (ok && event?.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> ledger.deleteKey(forward = false)
                KeyEvent.KEYCODE_FORWARD_DEL -> ledger.deleteKey(forward = true)
            }
        }
        return ok
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val ok = super.setSelection(start, end)
        if (ok) ledger.select(start, end)
        return ok
    }

    override fun performContextMenuAction(id: Int): Boolean {
        val ok = super.performContextMenuAction(id)
        if (ok) when (id) {
            android.R.id.paste, android.R.id.pasteAsPlainText -> ledger.unknownInsertAtSelection()
            android.R.id.cut -> if (ledger.selEnd > ledger.selStart) ledger.deleteKey(forward = false)
        }
        return ok
    }

    override fun commitContent(
        inputContentInfo: android.view.inputmethod.InputContentInfo,
        flags: Int,
        opts: android.os.Bundle?
    ): Boolean {
        val ok = super.commitContent(inputContentInfo, flags, opts)
        if (ok) ledger.unknownInsertAtSelection()
        return ok
    }

    override fun <T> withOrigin(origin: Origin, block: () -> T): T = ledger.labelled(origin, block)
}

/** A connection that can tag the commits made inside a block with a known [Origin]. */
interface OriginAwareConnection {
    fun <T> withOrigin(origin: Origin, block: () -> T): T
}

/** Tags commits in [block] with [origin] when Proof of Thought is recording; otherwise just runs it. */
fun <T> InputConnection.withOrigin(origin: Origin, block: () -> T): T =
    (this as? OriginAwareConnection)?.withOrigin(origin, block) ?: block()
