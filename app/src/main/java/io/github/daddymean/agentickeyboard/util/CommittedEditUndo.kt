package io.github.daddymean.agentickeyboard.util

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/** One editor transaction; never searches for matching text elsewhere in a draft. */
data class CommittedEditUndo internal constructor(
    private val connection: InputConnection,
    val start: Int,
    val replacement: String,
    val original: String,
    val caretOffset: Int
) {
    fun undoIfUnchanged(current: InputConnection): Boolean {
        if (current !== connection) return false
        val caret = current.absoluteCaret() ?: return false
        if (caret != start + caretOffset) return false
        val before = replacement.substring(0, caretOffset)
        val after = replacement.substring(caretOffset)
        if (current.getTextBeforeCursor(before.length, 0)?.toString() != before ||
            current.getTextAfterCursor(after.length, 0)?.toString() != after) return false
        current.beginBatchEdit()
        return try {
            if (!current.deleteSurroundingText(before.length, after.length)) false
            else current.commitText(original, 1)
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

/** Editors that cannot report their selection safely simply get no range undo. */
fun InputConnection.captureCommittedEditUndo(
    original: String, replacement: String, cursorOffset: Int?
): CommittedEditUndo? {
    val caret = absoluteCaret() ?: return null
    val offset = cursorOffset?.takeIf { it in 0..replacement.length } ?: replacement.length
    if (caret < offset) return null
    val before = replacement.substring(0, offset)
    val after = replacement.substring(offset)
    if (getTextBeforeCursor(before.length, 0)?.toString() != before ||
        getTextAfterCursor(after.length, 0)?.toString() != after) return null
    return CommittedEditUndo(this, caret - offset, replacement, original, offset)
}
