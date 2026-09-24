package io.github.daddymean.agentickeyboard.util

import android.view.inputmethod.InputConnection

/**
 * Commits [text] and leaves the caret [cursorOffset] characters into it, so an
 * expanded template can drop the user where they actually start writing.
 *
 * `commitText`'s own cursor argument cannot land inside the committed text — values
 * at or below zero are relative to its start and positive ones to its end — so the
 * text goes in as two commits and the second one asks for the caret just before
 * itself. A null or out-of-range [cursorOffset] commits normally, caret at the end.
 */
fun InputConnection.commitTextWithCaret(text: String, cursorOffset: Int?) {
    if (cursorOffset == null || cursorOffset !in 0 until text.length) {
        commitText(text, 1)
        return
    }
    beginBatchEdit()
    try {
        val head = text.substring(0, cursorOffset)
        if (head.isNotEmpty()) commitText(head, 1)
        commitText(text.substring(cursorOffset), 0)
    } finally {
        endBatchEdit()
    }
}
