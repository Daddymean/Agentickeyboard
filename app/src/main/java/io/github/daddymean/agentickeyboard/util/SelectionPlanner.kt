package io.github.daddymean.agentickeyboard.util

import java.text.BreakIterator

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

    /** Zero-width joiner and non-joiner: they bind letters inside one word. */
    private const val ZWJ = '\u200D'
    private const val ZWNJ = '\u200C'

    /**
     * Characters that count as part of a word for the word-wise commands.
     *
     * Combining marks have to be included. A mark is a single BMP character that
     * is not a letter, so treating it as a word break makes the word scan stop
     * *at* the mark — inside a user-visible character — rather than skipping past
     * it. In Devanagari "नमस्ते" the virama is such a mark, so the word commands
     * stopped after three characters, and a cut or an AI rewrite of that
     * "word" would take a fragment and orphan the mark. NFD Latin ("cafe" plus a
     * combining acute) fails the same way.
     *
     * This is a different mechanism from the surrogate-pair problem the character
     * commands solve with a grapheme iterator, which is why fixing those did not
     * fix these.
     */
    private fun isWordChar(c: Char): Boolean {
        if (c.isLetterOrDigit() || c == '\'' || c == '_') return true
        return when (c.category) {
            CharCategory.NON_SPACING_MARK,
            CharCategory.COMBINING_SPACING_MARK,
            CharCategory.ENCLOSING_MARK -> true
            else -> c == ZWJ || c == ZWNJ
        }
    }

    /**
     * Snaps [offset] back onto a grapheme boundary, and [snapForward] snaps on.
     *
     * Defense in depth behind [isWordChar]: whatever the word scan decides, a
     * command can then never hand the editor an offset that sits inside a
     * cluster. Every offset in plain ASCII is already a boundary, so these are
     * identity there.
     */
    private fun snapBack(text: String, offset: Int): Int {
        if (offset <= 0 || offset >= text.length) return offset.coerceIn(0, text.length)
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        if (iterator.isBoundary(offset)) return offset
        val previous = iterator.preceding(offset)
        return if (previous == BreakIterator.DONE) 0 else previous
    }

    private fun snapForward(text: String, offset: Int): Int {
        if (offset <= 0 || offset >= text.length) return offset.coerceIn(0, text.length)
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        if (iterator.isBoundary(offset)) return offset
        val next = iterator.following(offset)
        return if (next == BreakIterator.DONE) text.length else next
    }

    /**
     * Maps a snapshot-relative range onto absolute document offsets.
     *
     * `ExtractedText` reports its selection relative to the snapshot it returned,
     * while `InputConnection.setSelection` takes absolute offsets; they coincide
     * only when the editor extracted from the document start. Kept here, as a
     * pure function, so the mapping has a test instead of living inline in the
     * service where it cannot have one.
     */
    fun toDocumentRange(planned: SelectionRange, startOffset: Int): SelectionRange {
        val base = startOffset.coerceAtLeast(0)
        return SelectionRange(planned.start + base, planned.end + base)
    }

    fun plan(text: String, selection: SelectionRange, command: SelectionCommand): SelectionRange {
        val len = text.length
        val anchor = selection.start.coerceIn(0, len)
        val active = selection.end.coerceIn(0, len)
        val current = SelectionRange(anchor, active)

        return when (command) {
            SelectionCommand.MoveLeft ->
                if (!current.isCollapsed) SelectionRange.caret(current.min)
                else SelectionRange.caret(graphemeBefore(text, active))

            SelectionCommand.MoveRight ->
                if (!current.isCollapsed) SelectionRange.caret(current.max)
                else SelectionRange.caret(graphemeAfter(text, active))

            SelectionCommand.MoveWordLeft ->
                SelectionRange.caret(previousWordBoundary(text, current.min))

            SelectionCommand.MoveWordRight ->
                SelectionRange.caret(nextWordBoundary(text, current.max))

            SelectionCommand.ExtendLeft ->
                SelectionRange(anchor, graphemeBefore(text, active))

            SelectionCommand.ExtendRight ->
                SelectionRange(anchor, graphemeAfter(text, active))

            SelectionCommand.ExtendWordLeft ->
                SelectionRange(anchor, previousWordBoundary(text, active))

            SelectionCommand.ExtendWordRight ->
                SelectionRange(anchor, nextWordBoundary(text, active))

            // Correct for the text handed in, but the IME service does not route
            // SelectAll here: it asks the editor instead, because [text] may be a
            // partial extraction and selecting all of it would miss the rest of
            // the document. Kept so the planner answers every command on its own
            // terms and stays independently testable.
            SelectionCommand.SelectAll -> SelectionRange(0, len)

            SelectionCommand.SelectWord -> selectWordAt(text, active)

            SelectionCommand.SelectLine -> selectLineAt(text, active)

            SelectionCommand.Collapse -> SelectionRange.caret(active)
        }
    }

    /**
     * The offset one user-visible character before [from].
     *
     * Stepping by one `Char` would step by one UTF-16 code unit, which lands
     * inside a surrogate pair for any emoji or other supplementary character
     * and corrupts it on the next edit. A grapheme iterator also keeps combining
     * marks, skin-tone modifiers and ZWJ sequences (a family emoji is a single
     * cluster of many code units) intact, which code-point stepping alone does
     * not. An offset that arrives mid-cluster resolves to that cluster's start.
     */
    fun graphemeBefore(text: String, offset: Int): Int {
        if (offset <= 0) return 0
        if (offset > text.length) return text.length
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val previous = iterator.preceding(offset)
        return if (previous == BreakIterator.DONE) 0 else previous
    }

    /** The offset one user-visible character after [from], mirroring [graphemeBefore]. */
    fun graphemeAfter(text: String, offset: Int): Int {
        val len = text.length
        if (offset >= len) return len
        if (offset < 0) return 0
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        val next = iterator.following(offset)
        return if (next == BreakIterator.DONE) len else next
    }

    /**
     * Start of the word at or before [from]: skips any run of non-word
     * characters first, so a caret sitting after "hello, " lands on the "h".
     */
    fun previousWordBoundary(text: String, from: Int): Int {
        var i = from.coerceIn(0, text.length)
        while (i > 0 && !isWordChar(text[i - 1])) i--
        while (i > 0 && isWordChar(text[i - 1])) i--
        return snapBack(text, i)
    }

    /** End of the word at or after [from], mirroring [previousWordBoundary]. */
    fun nextWordBoundary(text: String, from: Int): Int {
        val len = text.length
        var i = from.coerceIn(0, len)
        while (i < len && !isWordChar(text[i])) i++
        while (i < len && isWordChar(text[i])) i++
        return snapForward(text, i)
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
            return SelectionRange(snapBack(text, start), snapForward(text, end))
        }

        // Caret is in whitespace or punctuation: fall back to the nearest word.
        val prevEnd = (caret downTo 1).firstOrNull { isWordChar(text[it - 1]) }
        if (prevEnd != null) {
            var start = prevEnd
            while (start > 0 && isWordChar(text[start - 1])) start--
            return SelectionRange(snapBack(text, start), snapForward(text, prevEnd))
        }
        val nextStart = (caret until len).firstOrNull { isWordChar(text[it]) }
        if (nextStart != null) {
            var end = nextStart
            while (end < len && isWordChar(text[end])) end++
            return SelectionRange(snapBack(text, nextStart), snapForward(text, end))
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
