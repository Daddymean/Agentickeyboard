package io.github.daddymean.agentickeyboard.util

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.lang.ref.WeakReference

/**
 * Exact-range undo for one committed replacement, for when the caret was left
 * inside it by a `{cursor}` template. A suffix check on the text before the caret
 * cannot find such a replacement, so this records where it landed instead and
 * reverts it only while the editor still shows it untouched, caret unmoved. It
 * never searches the draft for matching text elsewhere.
 *
 * Holds the connection weakly: a pending undo can outlive the editor it came from.
 */
class CommittedEditUndo internal constructor(
    connection: InputConnection,
    private val start: Int,
    private val replacement: String,
    private val original: String,
    private val caretOffset: Int
) {
    private val connection = WeakReference(connection)

    /** Restores [original] and returns true, or changes nothing and returns false. */
    fun undoIfUnchanged(current: InputConnection): Boolean {
        if (current !== connection.get()) return false
        val caret = current.absoluteCaret() ?: return false
        if (caret != start + caretOffset) return false
        val before = replacement.substring(0, caretOffset)
        val after = replacement.substring(caretOffset)
        if (current.getTextBeforeCursor(before.length, 0)?.toString() != before ||
            current.getTextAfterCursor(after.length, 0)?.toString() != after
        ) return false
        current.beginBatchEdit()
        return try {
            current.deleteSurroundingText(before.length, after.length) && current.commitText(original, 1)
        } finally {
            current.endBatchEdit()
        }
    }
}

private fun InputConnection.absoluteCaret(): Int? {
    val extracted = getExtractedText(ExtractedTextRequest(), 0) ?: return null
    if (extracted.selectionStart < 0 || extracted.selectionStart != extracted.selectionEnd) return null
    return extracted.startOffset + extracted.selectionStart
}

/**
 * Call right after committing [replacement] (with the caret [cursorOffset] chars into
 * it, or at its end when null). Returns null when the editor cannot report its
 * caret or does not show [replacement] around it; such editors simply get no
 * range undo.
 */
fun InputConnection.captureCommittedEditUndo(
    original: String,
    replacement: String,
    cursorOffset: Int?
): CommittedEditUndo? {
    val caret = absoluteCaret() ?: return null
    val offset = cursorOffset?.takeIf { it in 0..replacement.length } ?: replacement.length
    if (caret < offset) return null
    val before = replacement.substring(0, offset)
    val after = replacement.substring(offset)
    if (getTextBeforeCursor(before.length, 0)?.toString() != before ||
        getTextAfterCursor(after.length, 0)?.toString() != after
    ) return null
    return CommittedEditUndo(this, caret - offset, replacement, original, offset)
}
