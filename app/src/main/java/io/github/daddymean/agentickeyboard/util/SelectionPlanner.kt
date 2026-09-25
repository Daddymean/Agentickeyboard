package io.github.daddymean.agentickeyboard.util

/**
 * A caret or selection range over the editor's whole text.
 *
 * [start] is the anchor and [end] is the moving edge, mirroring the contract of
 * `InputConnection.setSelection`: a backwards selection (end < start) is legal
 * and keeps the caret on the left, which is what "extend left" should feel like.
 */
data class SelectionRange(val start: Int, val end: Int) {
    val isCollapsed: Boolean get() = start == end
    val min: Int get() = minOf(start, end)
    val max: Int get() = maxOf(start, end)
    val length: Int get() = max - min

    companion object {
        fun caret(at: Int) = SelectionRange(at, at)
    }
}

/** The editing gestures the on-keyboard edit bar can ask for. */
enum class SelectionCommand {
    MoveLeft,
    MoveRight,
    MoveWordLeft,
    MoveWordRight,
    ExtendLeft,
    ExtendRight,
    ExtendWordLeft,
    ExtendWordRight,
    SelectAll,
    SelectWord,
    SelectLine,
    Collapse
}

/**
 * Clipboard gestures the edit bar hands straight to the host editor, which owns
 * the real clipboard and any permission prompt that comes with it. Kept as an
 * enum so the keyboard UI never has to name `android.R.id` constants.
 */
enum class EditClipboardAction { Cut, Copy, Paste }

/**
 * Pure selection arithmetic for the edit bar.
 *
 * The IME service owns the [android.view.inputmethod.InputConnection]; this
 * object only answers "given this text and this selection, where should the
 * selection go next". Keeping it free of Android types is what lets the whole
 * behaviour be covered by plain JVM unit tests in CI.
 */
object SelectionPlanner {

    /** Characters that count as part of a word for the word-wise commands. */
    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '\'' || c == '_'

    fun plan(text: String, selection: SelectionRange, command: SelectionCommand): SelectionRange {
        val len = text.length
        val anchor = selection.start.coerceIn(0, len)
        val active = selection.end.coerceIn(0, len)
        val current = SelectionRange(anchor, active)

        return when (command) {
            SelectionCommand.MoveLeft ->
                if (!current.isCollapsed) SelectionRange.caret(current.min)
                else SelectionRange.caret((active - 1).coerceAtLeast(0))

            SelectionCommand.MoveRight ->
                if (!current.isCollapsed) SelectionRange.caret(current.max)
                else SelectionRange.caret((active + 1).coerceAtMost(len))

            SelectionCommand.MoveWordLeft ->
                SelectionRange.caret(previousWordBoundary(text, current.min))

            SelectionCommand.MoveWordRight ->
                SelectionRange.caret(nextWordBoundary(text, current.max))

            SelectionCommand.ExtendLeft ->
                SelectionRange(anchor, (active - 1).coerceAtLeast(0))

            SelectionCommand.ExtendRight ->
                SelectionRange(anchor, (active + 1).coerceAtMost(len))

            SelectionCommand.ExtendWordLeft ->
                SelectionRange(anchor, previousWordBoundary(text, active))

            SelectionCommand.ExtendWordRight ->
                SelectionRange(anchor, nextWordBoundary(text, active))

            SelectionCommand.SelectAll -> SelectionRange(0, len)

            SelectionCommand.SelectWord -> selectWordAt(text, active)

            SelectionCommand.SelectLine -> selectLineAt(text, active)

            SelectionCommand.Collapse -> SelectionRange.caret(active)
        }
    }

    /**
     * Start of the word at or before [from]: skips any run of non-word
     * characters first, so a caret sitting after "hello, " lands on the "h".
     */
    fun previousWordBoundary(text: String, from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i > 0 && !isWordChar(text[i - 1])) i--
        while (i > 0 && isWordChar(text[i - 1])) i--
        return i
    }

    /** End of the word at or after [from], mirroring [previousWordBoundary]. */
    fun nextWordBoundary(text: String, from: Int): Int {
        val len = text.length
        var i = from.coerceIn(0, len)
        while (i < len && !isWordChar(text[i])) i++
        while (i < len && isWordChar(text[i])) i++
        return i
    }

    /**
     * The word touching [at]. A caret inside or on either edge of a word takes
     * that word; a caret stranded in whitespace prefers the word it just left,
     * then the one ahead, and collapses only when the text holds no word at all.
     */
    fun selectWordAt(text: String, at: Int): SelectionRange {
        val len = text.length
        val caret = at.coerceIn(0, len)

        val onWord = caret < len && isWordChar(text[caret])
        val afterWord = caret > 0 && isWordChar(text[caret - 1])

        if (onWord || afterWord) {
            var start = caret
            while (start > 0 && isWordChar(text[start - 1])) start--
            var end = caret
            while (end < len && isWordChar(text[end])) end++
            return SelectionRange(start, end)
        }

        // Caret is in whitespace or punctuation: fall back to the nearest word.
        val prevEnd = (caret downTo 1).firstOrNull { isWordChar(text[it - 1]) }
        if (prevEnd != null) {
            var start = prevEnd
            while (start > 0 && isWordChar(text[start - 1])) start--
            return SelectionRange(start, prevEnd)
        }
        val nextStart = (caret until len).firstOrNull { isWordChar(text[it]) }
        if (nextStart != null) {
            var end = nextStart
            while (end < len && isWordChar(text[end])) end++
            return SelectionRange(nextStart, end)
        }
        return SelectionRange.caret(caret)
    }

    /** The whole line containing [at], excluding its trailing newline. */
    fun selectLineAt(text: String, at: Int): SelectionRange {
        val len = text.length
        val caret = at.coerceIn(0, len)
        val start = text.lastIndexOf('\n', (caret - 1).coerceAtLeast(0))
            .let { if (it < 0 || caret == 0) 0 else it + 1 }
        val nextBreak = text.indexOf('\n', caret)
        val end = if (nextBreak < 0) len else nextBreak
        return SelectionRange(start, end)
    }
}
