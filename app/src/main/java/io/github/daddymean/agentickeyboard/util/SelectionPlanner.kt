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
    private const val ZWJ = 0x200D
    private const val ZWNJ = 0x200C

    /** U+2019, the apostrophe iOS, macOS and word processors actually insert. */
    private const val RIGHT_SINGLE_QUOTE = 0x2019

    /**
     * A code point that can begin a word on its own.
     *
     * It takes a code point, not a `Char`. Above the BMP a `Char` is only half a
     * surrogate pair, whose category is `Cs` — never a letter. Classifying per
     * `Char` therefore finds no word at all in Adlam, Osage, Gothic or the
     * mathematical alphanumerics common in styled social text, and in mixed text
     * the scan halts at the pair mid-word.
     *
     * The apostrophes and the underscore are unconditional, which is the
     * behaviour `'` has always had here: "o'clock" and "don't_x" are each one
     * word, and a leading quote is taken along with the word after it. U+2019
     * joins them because iOS, macOS and most word processors substitute it for a
     * typed apostrophe, so it is what pasted prose contains — without it the
     * word scan stopped at the quote and "don’t" selected as "don". It follows
     * that `‘quoted’` takes its quotes into the word, exactly as `'quoted'`
     * already did; treating an apostrophe as word-forming only between two bases
     * would fix both at once, but that is a change to long-standing behaviour
     * rather than to this one, so it is left alone here.
     *
     * U+02BC MODIFIER LETTER APOSTROPHE needs no entry: its category is `Lm`,
     * so `isLetterOrDigit` already accepts it.
     */
    private fun isWordBase(codePoint: Int): Boolean =
        Character.isLetterOrDigit(codePoint) ||
            codePoint == '\''.code ||
            codePoint == '_'.code ||
            codePoint == RIGHT_SINGLE_QUOTE

    /**
     * A code point that extends a word it is attached to but cannot begin one.
     *
     * Combining marks have to count as part of a word. A mark is not a letter,
     * so treating it as a word break makes the word scan stop *at* the mark —
     * inside a user-visible character — rather than skipping past it. In
     * Devanagari "नमस्ते" the virama is such a mark, so the word commands
     * stopped after three characters, and a cut or an AI rewrite of that "word"
     * would take a fragment and orphan the mark. NFD Latin ("cafe" plus a
     * combining acute) fails the same way. That is a different mechanism from
     * the surrogate-pair problem the character commands solve with a grapheme
     * iterator, which is why fixing those did not fix these.
     */
    private fun isWordExtender(codePoint: Int): Boolean {
        if (codePoint == ZWJ || codePoint == ZWNJ) return true
        return when (Character.getType(codePoint)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }

    /**
     * Whether the code point starting at [index] is part of a word.
     *
     * An extender counts only when a base precedes it, through any run of other
     * extenders. Classifying extenders on their own made emoji read as words,
     * because a ZWJ sequence is joiners between pictographs and U+FE0F VARIATION
     * SELECTOR-16 — the one that makes "❤️" render as an emoji — is category
     * `Mn`. So "❤️hello" selected the heart along with the word, and on a device,
     * where the grapheme iterator builds whole extended clusters, word-right ran
     * to the end of "👨‍👩‍👧" as though it were a word. Requiring a base skips such
     * a cluster instead: no pictograph is a letter or a digit, so no joiner or
     * selector inside one ever has a base behind it.
     *
     * The marks this exists for are unaffected, since they follow a letter: the
     * virama in "नमस्ते", the acute in NFD "café", the ZWNJ in Persian
     * "می‌خواهم", the Adlam lengthener after its letters.
     */
    private class WordClassifier(private val text: String) {
        private val wordStarts = BooleanArray(text.length)

        init {
            // Classify each code point once. An extender inherits the preceding
            // run's base state; a pictograph or separator resets that state.
            var attachedToBase = false
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                attachedToBase = isWordBase(codePoint) ||
                    (isWordExtender(codePoint) && attachedToBase)
                wordStarts[index] = attachedToBase
                index += Character.charCount(codePoint)
            }
        }

        fun hasWordAt(index: Int): Boolean =
            index < text.length && wordStarts[index]

        fun hasWordBefore(index: Int): Boolean =
            index > 0 && wordStarts[index - Character.charCount(text.codePointBefore(index))]
    }

    /** Steps [index] one code point left, regardless of what it holds. */
    private fun stepLeft(text: String, index: Int): Int =
        index - Character.charCount(text.codePointBefore(index))

    /** Steps [index] one code point right, regardless of what it holds. */
    private fun stepRight(text: String, index: Int): Int =
        index + Character.charCount(text.codePointAt(index))

    /**
     * Snaps [offset] back onto a grapheme boundary, and [snapForward] snaps on.
     *
     * Defense in depth behind [WordClassifier]: whatever the word scan decides, a
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
        val words = WordClassifier(text)
        var i = from.coerceIn(0, text.length)
        while (i > 0 && !words.hasWordBefore(i)) i = stepLeft(text, i)
        while (i > 0 && words.hasWordBefore(i)) i = stepLeft(text, i)
        return snapBack(text, i)
    }

    /** End of the word at or after [from], mirroring [previousWordBoundary]. */
    fun nextWordBoundary(text: String, from: Int): Int {
        val words = WordClassifier(text)
        val len = text.length
        var i = from.coerceIn(0, len)
        while (i < len && !words.hasWordAt(i)) i = stepRight(text, i)
        while (i < len && words.hasWordAt(i)) i = stepRight(text, i)
        return snapForward(text, i)
    }

    /**
     * The word touching [at]. A caret inside or on either edge of a word takes
     * that word; a caret stranded in whitespace prefers the word it just left,
     * then the one ahead, and collapses only when the text holds no word at all.
     */
    fun selectWordAt(text: String, at: Int): SelectionRange {
        val words = WordClassifier(text)
        val len = text.length
        val caret = at.coerceIn(0, len)

        val onWord = words.hasWordAt(caret)
        val afterWord = words.hasWordBefore(caret)

        if (onWord || afterWord) {
            var start = caret
            while (start > 0 && words.hasWordBefore(start)) start = stepLeft(text, start)
            var end = caret
            while (end < len && words.hasWordAt(end)) end = stepRight(text, end)
            return SelectionRange(snapBack(text, start), snapForward(text, end))
        }

        // Caret is in whitespace or punctuation: fall back to the nearest word.
        // These walk with plain Int offsets rather than a nullable "found index",
        // so reaching the edge is the "nothing found" case and there is nothing
        // to smart-cast.
        var back = caret
        while (back > 0 && !words.hasWordBefore(back)) back = stepLeft(text, back)
        if (back > 0) {
            var start = back
            while (start > 0 && words.hasWordBefore(start)) start = stepLeft(text, start)
            return SelectionRange(snapBack(text, start), snapForward(text, back))
        }

        var forward = caret
        while (forward < len && !words.hasWordAt(forward)) forward = stepRight(text, forward)
        if (forward < len) {
            var end = forward
            while (end < len && words.hasWordAt(end)) end = stepRight(text, end)
            return SelectionRange(snapBack(text, forward), snapForward(text, end))
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
