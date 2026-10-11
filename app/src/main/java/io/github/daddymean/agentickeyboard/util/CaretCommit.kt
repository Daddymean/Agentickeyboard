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
fun InputConnection.commitTextWithCaret(text: String, cursorOffset: Int?, typed: Boolean = false) {
    // KEYBOARD-024: only a caller that knows this is the user's typing may say so.
    val commit: (CharSequence, Int) -> Unit = { t, pos -> if (typed) commitTyped(t, pos) else commitInserted(t, pos) }
    if (cursorOffset == null || cursorOffset !in 0 until text.length) {
        commit(text, 1)
        return
    }
    beginBatchEdit()
    try {
        val head = text.substring(0, cursorOffset)
        if (head.isNotEmpty()) commit(head, 1)
        commit(text.substring(cursorOffset), 0)
    } finally {
        endBatchEdit()
    }
}
