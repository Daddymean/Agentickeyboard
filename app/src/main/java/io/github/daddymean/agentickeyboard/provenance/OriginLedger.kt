package io.github.daddymean.agentickeyboard.provenance

/**
 * Where a character in the field came from, as the keyboard witnessed it
 * (Proof of Thought, docs/superpowers/specs/2026-11-28-proof-of-thought-design.md §4).
 *
 * [TYPED] and [SWIPED] are human input. [ASSISTED] is a human choice of a
 * keyboard-proposed word (suggestion pick, auto-fix). [AI], [PASTED] and [INSERTED]
 * (keyboard-inserted text from a path not labelled more precisely) never count as
 * human. [UNWITNESSED] is anything the keyboard did not insert itself.
 */
enum class Origin { UNWITNESSED, TYPED, SWIPED, ASSISTED, AI, PASTED, INSERTED }

/**
 * Proof of Thought slice 1: a per-field, memory-only model of the editor's text with
 * one [Origin] per UTF-16 unit.
 *
 * The keyboard applies its own edits here as it makes them (through
 * [ProvenanceInputConnection]). It never trusts the model blindly: [resyncBefore]
 * (on every onUpdateSelection read) and [reconcile] (before a receipt) compare it with
 * the editor's real text, and anything that differs is retagged [UNWITNESSED].
 *
 * **Invariant:** characters only ever get a non-[UNWITNESSED] tag from an edit the
 * keyboard recorded, and a resync or reconcile never adds one, so the count for any
 * origin never exceeds the characters the keyboard actually inserted with it.
 * Unknown edits (paste menu, rich content, the app changing the field) need no
 * handling: they surface as differences and become [UNWITNESSED].
 *
 * Privacy: holds the field text in memory like the editor mirror and the draft
 * timeline. Never persisted, never logged, cleared by [reset]. The service keeps it
 * inactive in sensitive fields and when Proof of Thought is off.
 */
class OriginLedger(private val maxChars: Int = MAX_CHARS) {

    private val text = StringBuilder()
    private var tags = ByteArray(INITIAL_CAPACITY)

    /** Recording only while true; every method is a no-op otherwise. */
    var active = false
        private set

    /** The field grew past [maxChars]: no receipt may be issued for this session. */
    var overflowed = false
        private set

    var selStart = 0
        private set
    var selEnd = 0
        private set

    val stats = CompositionStats()

    private var labelOverride: Origin? = null

    /** Where an edit the keyboard asked for, but cannot see the text of, was inserted. */
    private var pendingUnknownAt = -1

    val length: Int get() = text.length

    /** Starts a field session; [initialSelStart]/[initialSelEnd] come from EditorInfo. */
    fun begin(initialSelStart: Int, initialSelEnd: Int, nowMillis: Long) {
        reset()
        active = true
        val start = initialSelStart.coerceAtLeast(0)
        val end = initialSelEnd.coerceAtLeast(start)
        stats.start(nowMillis)
        if (!padTo(end)) return
        selStart = start
        selEnd = end
    }

    /** Drops everything: the field finished, or Proof of Thought is off. */
    fun reset() {
        active = false
        overflowed = false
        text.setLength(0)
        tags = ByteArray(INITIAL_CAPACITY)
        selStart = 0
        selEnd = 0
        labelOverride = null
        pendingUnknownAt = -1
        stats.clear()
    }

    /** Runs [block] with every commit inside it tagged [origin] (a known insert path). */
    fun <T> labelled(origin: Origin, block: () -> T): T {
        val previous = labelOverride
        labelOverride = origin
        try {
            return block()
        } finally {
            labelOverride = previous
        }
    }

    /** [default] unless a [labelled] block names the origin. */
    fun originFor(default: Origin): Origin = labelOverride ?: default

    /** commitText semantics: replaces the selection and places the caret per [newCursorPosition]. */
    fun commit(inserted: CharSequence, origin: Origin, newCursorPosition: Int, nowMillis: Long) {
        if (!active) return
        pendingUnknownAt = -1
        val start = selStart
        val end = selEnd
        if (end != text.length || start != end) stats.nonlinearEdit()
        if (end > start) stats.deleted(end - start)
        if (!replace(start, end, inserted, origin)) return
        val insertedEnd = start + inserted.length
        val caret = if (newCursorPosition > 0) insertedEnd + newCursorPosition - 1 else start + newCursorPosition
        setCaret(caret.coerceIn(0, text.length))
        if (origin == Origin.TYPED) stats.typedKey(nowMillis)
    }

    /** deleteSurroundingText semantics (UTF-16 units; the selection itself is kept). */
    fun deleteSurrounding(beforeLength: Int, afterLength: Int) {
        if (!active) return
        pendingUnknownAt = -1
        val after = afterLength.coerceAtLeast(0).coerceAtMost(text.length - selEnd)
        val before = beforeLength.coerceAtLeast(0).coerceAtMost(selStart)
        if (after == 0 && before == 0) return
        if (selEnd != text.length || after > 0) stats.nonlinearEdit()
        if (after > 0) removeRange(selEnd, selEnd + after)
        if (before > 0) {
            removeRange(selStart - before, selStart)
            selStart -= before
            selEnd -= before
        }
        stats.deleted(before + after)
    }

    /** deleteSurroundingTextInCodePoints semantics. */
    fun deleteSurroundingInCodePoints(beforeCodePoints: Int, afterCodePoints: Int) {
        if (!active) return
        deleteSurrounding(
            unitsBefore(selStart, beforeCodePoints.coerceAtLeast(0)),
            unitsAfter(selEnd, afterCodePoints.coerceAtLeast(0))
        )
    }

    /** A DEL ([forward] false) or FORWARD_DEL key: the selection, else one code point. */
    fun deleteKey(forward: Boolean) {
        if (!active) return
        if (selEnd > selStart) {
            if (selEnd != text.length) stats.nonlinearEdit()
            stats.deleted(selEnd - selStart)
            removeRange(selStart, selEnd)
            selEnd = selStart
        } else if (forward) {
            deleteSurrounding(0, unitsAfter(selEnd, 1))
        } else {
            deleteSurrounding(unitsBefore(selStart, 1), 0)
        }
    }

    /**
     * The keyboard asked the editor for an edit whose text it cannot see (its Paste
     * action, rich content): the selection is replaced by something unknown. The next
     * reported caret says how long it was ([select]); the resync then fills in the
     * real text as [Origin.UNWITNESSED] without shifting the text after it.
     */
    fun unknownInsertAtSelection() {
        if (!active) return
        if (selEnd > selStart) {
            stats.deleted(selEnd - selStart)
            removeRange(selStart, selEnd)
            selEnd = selStart
        }
        if (active) pendingUnknownAt = selStart
    }

    /** setSelection by the keyboard, or the caret onUpdateSelection reported. */
    fun select(start: Int, end: Int) {
        if (!active || start < 0 || end < 0) return
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        val unknownAt = pendingUnknownAt
        pendingUnknownAt = -1
        if (unknownAt in 0..text.length && lo == hi && lo > unknownAt) {
            if (!insertUnknown(unknownAt, lo - unknownAt)) return
        }
        if (!padTo(hi)) return
        selStart = lo
        selEnd = hi
    }

    /**
     * After a real read of the text before the caret (onUpdateSelection): makes the
     * model agree with [actualBefore], which ends at [selStart]. Differences become
     * [UNWITNESSED].
     */
    fun resyncBefore(actualBefore: CharSequence) {
        if (!active) return
        val missing = actualBefore.length - selStart
        if (missing > 0) {
            // The editor has more text before the caret than the model knows of.
            if (!insertUnknown(0, missing)) return
            selStart += missing
            selEnd += missing
        }
        reconcileRange(selStart - actualBefore.length, selStart, actualBefore)
    }

    /** Before a receipt: [actual] is the whole field, freshly read. */
    fun reconcile(actual: CharSequence) {
        if (!active) return
        reconcileRange(0, text.length, actual)
        selStart = selStart.coerceAtMost(text.length)
        selEnd = selEnd.coerceIn(selStart, text.length)
    }

    /** The model's text; callers compare it with the editor (tests, receipts). */
    fun textSnapshot(): String = text.toString()

    /** Characters per origin, every origin present (zero when absent). */
    fun counts(): Map<Origin, Int> {
        val counts = IntArray(ORIGINS.size)
        for (i in 0 until text.length) counts[tags[i].toInt()]++
        return ORIGINS.associateWith { counts[it.ordinal] }
    }

    /** The tag of one character (tests). */
    fun originAt(index: Int): Origin = ORIGINS[tags[index].toInt()]

    // --- editing primitives --------------------------------------------------------

    /** False when the edit would pass [maxChars]: the session stops recording ([overflow]). */
    private fun replace(start: Int, end: Int, inserted: CharSequence, origin: Origin): Boolean {
        val newLength = text.length - (end - start) + inserted.length
        if (newLength > maxChars) {
            overflow()
            return false
        }
        ensureCapacity(newLength)
        val tail = text.length - end
        System.arraycopy(tags, end, tags, start + inserted.length, tail)
        text.replace(start, end, inserted.toString())
        tags.fill(origin.ordinal.toByte(), start, start + inserted.length)
        return true
    }

    /** Too long to vouch for: drop the text, stop recording, remember why. */
    private fun overflow() {
        reset()
        overflowed = true
    }

    private fun removeRange(start: Int, end: Int) {
        replace(start, end, "", Origin.UNWITNESSED)
    }

    private fun insertUnknown(at: Int, count: Int): Boolean =
        count <= 0 || replace(at, at, UNKNOWN.toString().repeat(count), Origin.UNWITNESSED)

    private fun padTo(length: Int): Boolean =
        length <= text.length || insertUnknown(text.length, length - text.length)

    private fun setCaret(position: Int) {
        selStart = position
        selEnd = position
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= tags.size) return
        tags = tags.copyOf(maxOf(needed, tags.size * 2))
    }

    /**
     * Replaces model range [from, to) with [actual]: the common prefix and suffix keep
     * their tags, the differing middle is [actual]'s text tagged [UNWITNESSED]. A
     * matched character keeps its tag only if it is the same character, so no tag is
     * ever created here.
     */
    private fun reconcileRange(from: Int, to: Int, actual: CharSequence) {
        val modelLength = to - from
        var prefix = 0
        val maxPrefix = minOf(modelLength, actual.length)
        while (prefix < maxPrefix && text[from + prefix] == actual[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(modelLength, actual.length) - prefix
        while (suffix < maxSuffix && text[to - 1 - suffix] == actual[actual.length - 1 - suffix]) suffix++
        val replaceStart = from + prefix
        val replaceEnd = to - suffix
        val middle = actual.subSequence(prefix, actual.length - suffix)
        if (replaceEnd == replaceStart && middle.isEmpty()) return
        if (!replace(replaceStart, replaceEnd, middle, Origin.UNWITNESSED)) return
        val delta = middle.length - (replaceEnd - replaceStart)
        if (selStart >= replaceEnd) selStart += delta else if (selStart > replaceStart) selStart = replaceStart + middle.length
        if (selEnd >= replaceEnd) selEnd += delta else if (selEnd > replaceStart) selEnd = replaceStart + middle.length
    }

    private fun unitsBefore(offset: Int, codePoints: Int): Int {
        var i = offset
        var n = 0
        while (n < codePoints && i > 0) {
            i -= if (i >= 2 && Character.isLowSurrogate(text[i - 1]) && Character.isHighSurrogate(text[i - 2])) 2 else 1
            n++
        }
        return offset - i
    }

    private fun unitsAfter(offset: Int, codePoints: Int): Int {
        var i = offset
        var n = 0
        while (n < codePoints && i < text.length) {
            i += if (i + 1 < text.length && Character.isHighSurrogate(text[i]) && Character.isLowSurrogate(text[i + 1])) 2 else 1
            n++
        }
        return i - offset
    }

    companion object {
        /** No receipt for fields longer than this (spec §4.3). */
        const val MAX_CHARS = 20_000
        private const val INITIAL_CAPACITY = 256
        /** Placeholder for text the editor holds but the model has not seen; never equals real text. */
        const val UNKNOWN = '￿'
        private val ORIGINS = Origin.entries
    }
}
