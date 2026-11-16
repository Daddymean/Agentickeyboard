package io.github.daddymean.agentickeyboard.util

/**
 * LAB-001: avoid a synchronous InputConnection round trip on the keystroke path.
 *
 * onUpdateSelection already hands the IME the new selection bounds. When they
 * are known and collapsed (a plain caret, which is every ordinary keystroke),
 * the editor's selected text is empty by definition, so asking the editor for
 * it with getSelectedText(0) is a wasted binder call on the main thread.
 *
 * Unknown bounds (negative, e.g. from onStartInput) or a real range still read
 * the editor, so whitespace-only and app-specific selections behave exactly as
 * before.
 */
object EditorSelectionProbe {

    /** True when [selStart]/[selEnd] are known and describe a plain caret. */
    fun isCollapsed(selStart: Int, selEnd: Int): Boolean = selStart >= 0 && selStart == selEnd

    /**
     * The selected text: "" without calling [read] for a known collapsed caret,
     * otherwise whatever the editor returns (null when it cannot say).
     */
    inline fun selectedText(selStart: Int, selEnd: Int, read: () -> CharSequence?): String? =
        if (isCollapsed(selStart, selEnd)) "" else read()?.toString()
}
