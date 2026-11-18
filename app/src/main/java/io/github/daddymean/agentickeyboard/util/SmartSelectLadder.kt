package io.github.daddymean.agentickeyboard.util

import java.text.BreakIterator
import java.util.Locale

/*
 * LAB-011: semantic smart select.
 *
 * Hold the space bar, then slide: the selection climbs a ladder of units around
 * the caret, one haptic tick per rung, and slides back down the same way. Other
 * keyboards jump word -> sentence or only extend by words; this ladder also knows
 * the *things* people actually want to grab: a whole link, email, phone number,
 * @handle, amount or time, the inside of quotes or brackets, and a clause.
 *
 * Pure Kotlin, no Android types, nothing stored: the ladder is computed from the
 * text the edit bar already extracts and is thrown away when the finger lifts.
 */

/** What a rung of the ladder covers. [label] is what the key preview shows. */
enum class SmartUnit(val label: String) {
    Word("Word"),
    Link("Link"),
    Email("Email"),
    Phone("Phone"),
    Handle("Tag"),
    Amount("Amount"),
    Time("Time"),
    Quote("Quote"),
    Clause("Clause"),
    Sentence("Sentence"),
    Paragraph("Paragraph"),
    All("All")
}

data class SmartSelectStep(val unit: SmartUnit, val range: SelectionRange)

object SmartSelectLadder {

    /** Longer texts only get word/sentence/paragraph/all, to keep the hold instant. */
    const val MAX_SCAN_CHARS = 20_000

    private val entityPatterns: List<Pair<SmartUnit, Regex>> = listOf(
        SmartUnit.Email to Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"""),
        SmartUnit.Link to Regex("""(?:https?://|www\.)[^\s<>"'()\[\]{}]+[^\s<>"'()\[\]{}.,;:!?]"""),
        SmartUnit.Phone to Regex("""(?<![\w])\+?\d[\d\s().\-]{6,}\d(?![\w])"""),
        SmartUnit.Handle to Regex("""(?<![\w@#])[@#][A-Za-z0-9_][A-Za-z0-9_.]*[A-Za-z0-9_]?"""),
        SmartUnit.Time to Regex(
            """(?i)\b\d{1,2}(?::\d{2})?\s?(?:am|pm)(?:\s?(?:PT|PST|PDT|ET|EST|EDT|CT|CST|CDT|MT|MST|MDT|UTC|GMT))?\b|\b\d{1,2}:\d{2}(?:\s?(?:PT|ET|CT|MT|UTC|GMT))?\b"""
        ),
        SmartUnit.Amount to Regex(
            """(?i)[$€£¥]\s?\d[\d,]*(?:\.\d+)?(?:\s?[kmb])?\b|\b\d[\d,]*(?:\.\d+)?\s?(?:%|usd|eur|gbp|km/h|mph|km|mi|kg|lbs?|g|oz|cm|mm|m|ft|in|°[cf]|ml|l|gal|miles?|hours?|hrs?|mins?|minutes?|days?)(?![A-Za-z])"""
        )
    )

    private val pairs = listOf('"' to '"', '“' to '”', '‘' to '’', '(' to ')', '[' to ']', '{' to '}', '«' to '»')

    /**
     * The rungs for a caret (or the min edge of a selection) at [caret]. Every
     * rung strictly contains the one before it, so sliding always grows the
     * selection and never jumps sideways. Empty when there is no word to start from.
     */
    fun ladder(text: String, caret: Int, locale: Locale = Locale.getDefault()): List<SmartSelectStep> {
        val len = text.length
        if (len == 0) return emptyList()
        val at = caret.coerceIn(0, len)
        val steps = ArrayList<SmartSelectStep>(8)

        fun offer(unit: SmartUnit, range: SelectionRange?) {
            if (range == null || range.length == 0) return
            val previous = steps.lastOrNull()?.range
            if (previous == null || (range.min <= previous.min && range.max >= previous.max && range.length > previous.length)) {
                steps += SmartSelectStep(unit, SelectionRange(range.min, range.max))
            }
        }

        offer(SmartUnit.Word, SelectionPlanner.selectWordAt(text, at))
        if (len <= MAX_SCAN_CHARS) {
            val scope = paragraphAt(text, at)
            entityAt(text, at, scope)?.let { offer(it.unit, it.range) }
            enclosureAt(text, at, scope)?.let { offer(SmartUnit.Quote, it) }
            val sentence = sentenceAt(text, at, scope, locale)
            offer(SmartUnit.Clause, clauseAt(text, at, sentence))
            offer(SmartUnit.Sentence, sentence)
            offer(SmartUnit.Paragraph, scope)
        } else {
            offer(SmartUnit.Sentence, sentenceAt(text, at, paragraphAt(text, at), locale))
            offer(SmartUnit.Paragraph, paragraphAt(text, at))
        }
        offer(SmartUnit.All, SelectionRange(0, len))
        return steps
    }

    /** The entity (link, email, ...) touching [at], earliest pattern wins on ties. */
    fun entityAt(text: String, at: Int, scope: SelectionRange = SelectionRange(0, text.length)): SmartSelectStep? {
        val window = text.substring(scope.min, scope.max)
        val local = at - scope.min
        var best: SmartSelectStep? = null
        for ((unit, pattern) in entityPatterns) {
            for (match in pattern.findAll(window)) {
                if (match.range.first > local) break
                val end = match.range.last + 1
                if (local in match.range.first..end) {
                    val range = trimmed(text, SelectionRange(scope.min + match.range.first, scope.min + end))
                    if (best == null || range.length > best.range.length) best = SmartSelectStep(unit, range)
                }
            }
        }
        return best
    }

    /** Contents of the innermost quote or bracket pair around [at], within [scope]. */
    fun enclosureAt(text: String, at: Int, scope: SelectionRange): SelectionRange? {
        var best: SelectionRange? = null
        for ((open, close) in pairs) {
            val left = (if (open == close) lastUnpairedSymmetric(text, at, scope.min, open) else lastUnclosed(text, at, scope.min, open, close))
                ?: continue
            val right = if (open == close) text.indexOf(close, at).takeIf { it in at until scope.max }
            else firstUnopened(text, at, scope.max, open, close)
            right ?: continue
            val inner = SelectionRange(left + 1, right)
            if (inner.length > 0 && (best == null || inner.length < best.length)) best = inner
        }
        return best?.let { trimmed(text, it) }
    }

    private fun lastUnclosed(text: String, at: Int, floor: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = at - 1
        while (i >= floor) {
            when (text[i]) { close -> depth++; open -> if (depth == 0) return i else depth-- }
            i--
        }
        return null
    }

    private fun firstUnopened(text: String, at: Int, ceiling: Int, open: Char, close: Char): Int? {
        var depth = 0
        var i = at
        while (i < ceiling) {
            when (text[i]) { open -> depth++; close -> if (depth == 0) return i else depth-- }
            i++
        }
        return null
    }

    /** For "..." the quote is open at [at] when an odd number of them precede it on the paragraph. */
    private fun lastUnpairedSymmetric(text: String, at: Int, floor: Int, quote: Char): Int? {
        var count = 0
        var last = -1
        for (i in floor until at) if (text[i] == quote) { count++; last = i }
        return if (count % 2 == 1) last else null
    }

    fun sentenceAt(text: String, at: Int, scope: SelectionRange, locale: Locale = Locale.getDefault()): SelectionRange {
        val window = text.substring(scope.min, scope.max)
        val local = (at - scope.min).coerceIn(0, window.length)
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(window)
        val end = if (local >= window.length) window.length else it.following(local).let { e -> if (e == BreakIterator.DONE) window.length else e }
        val start = it.preceding(end).let { s -> if (s == BreakIterator.DONE) 0 else s }
        var range = trimmed(text, SelectionRange(scope.min + start, scope.min + end))
        // A caret parked in the gap after a sentence belongs to that sentence.
        if (at < range.min && start > 0) {
            val prevStart = it.preceding(start).let { s -> if (s == BreakIterator.DONE) 0 else s }
            range = trimmed(text, SelectionRange(scope.min + prevStart, scope.min + start))
        }
        return range
    }

    /** The clause around [at]: the stretch of [sentence] between , ; : – — separators. */
    fun clauseAt(text: String, at: Int, sentence: SelectionRange): SelectionRange? {
        val separators = charArrayOf(',', ';', ':', '–', '—')
        var start = sentence.min
        for (i in (at - 1).coerceAtMost(sentence.max - 1) downTo sentence.min) {
            if (text[i] in separators) { start = i + 1; break }
        }
        var end = sentence.max
        for (i in at until sentence.max) {
            if (text[i] in separators) { end = i; break }
        }
        if (start == sentence.min && end == sentence.max) return null
        return trimmed(text, SelectionRange(start, end))
    }

    /** The line around [at] (chat apps and notes use one line per paragraph). */
    fun paragraphAt(text: String, at: Int): SelectionRange = trimmed(text, SelectionPlanner.selectLineAt(text, at))

    private fun trimmed(text: String, range: SelectionRange): SelectionRange {
        var s = range.min
        var e = range.max
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return SelectionRange(s, e)
    }
}

/**
 * One space-bar hold. Starts on the first rung (the word), climbs or descends
 * one rung per [move] step, and can drop below the word back to the original
 * caret. Ranges come back in document coordinates ([startOffset] added), ready
 * for `InputConnection.setSelection`. Lives only for the hold; stores nothing.
 */
class SmartSelectSession(
    private val steps: List<SmartSelectStep>,
    private val startOffset: Int,
    private val origin: SelectionRange
) {
    /** -1 is the original caret/selection; 0 is the word. */
    var index: Int = 0
        private set

    val rungCount: Int get() = steps.size

    val label: String get() = if (index < 0) CARET_LABEL else steps[index].unit.label

    val documentRange: SelectionRange
        get() = if (index < 0) origin
        else steps[index].range.let { SelectionRange(it.min + startOffset, it.max + startOffset) }

    /** Moves [delta] rungs; returns true when the selection changed. */
    fun move(delta: Int): Boolean {
        val next = (index + delta).coerceIn(-1, steps.size - 1)
        if (next == index) return false
        index = next
        return true
    }

    companion object {
        const val CARET_LABEL = "Cursor"
    }
}
