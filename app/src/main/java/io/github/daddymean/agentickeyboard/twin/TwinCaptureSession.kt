package io.github.daddymean.agentickeyboard.twin

/**
 * KEYBOARD-024: decides, per editor, when a piece of text counts as one of
 * Keith's own committed messages for the legacy twin.
 *
 * A message is committed when he presses the keyboard's Send action, or when
 * the app clears the field by itself (most chat apps have their own send
 * button). Only text that was mostly typed on this keyboard in this field
 * qualifies: text already in the field when it opened, pasted text, clipboard
 * or snippet inserts, AI rewrites and other large jumps count as "inserted",
 * not typed. The keyboard never reads the screen for this, so another person's
 * messages cannot get in.
 *
 * Pure logic with no Android types: it runs on the main thread and does only
 * length arithmetic per keystroke. Sanitising, encrypting and writing happen
 * elsewhere, off the main thread.
 */
class TwinCaptureSession(
    private val maxTypedJump: Int = MAX_TYPED_JUMP,
    private val minOwnShare: Double = MIN_OWN_SHARE
) {
    /** Everything that must allow learning at the moment of capture. */
    data class Gate(
        val twinEnabled: Boolean,
        val twinPaused: Boolean,
        val learningPaused: Boolean,
        val sensitiveField: Boolean
    ) {
        val allowsCapture: Boolean
            get() = twinEnabled && !twinPaused && !learningPaused && !sensitiveField
    }

    /** One committed message, still in memory, not sanitised yet. */
    data class Candidate(val text: String, val packageName: String?, val atMillis: Long)

    private var packageName: String? = null
    private var gate: Gate = Gate(twinEnabled = false, twinPaused = false, learningPaused = false, sensitiveField = true)
    private var lastText: String = ""
    private var typed = 0
    private var inserted = 0
    // Marks set by the keyboard just before it edits the field itself. Editor
    // updates arrive asynchronously, and a replace is a delete then an insert,
    // so a mark stays valid for [MARK_WINDOW_MS] and until it is used.
    private var insertMarkUntil = Long.MIN_VALUE
    private var deleteMarkUntil = Long.MIN_VALUE

    /** A new editor gained focus; [initialText] is whatever the app pre-filled. */
    fun start(packageName: String?, initialText: String, gate: Gate) {
        this.packageName = packageName
        this.gate = gate
        lastText = initialText
        typed = 0
        inserted = initialText.length
        insertMarkUntil = Long.MIN_VALUE
        deleteMarkUntil = Long.MIN_VALUE
    }

    /** Settings or field state changed mid-session. A closed gate also drops what was counted. */
    fun updateGate(gate: Gate) {
        this.gate = gate
        if (!gate.allowsCapture) resetCounts(lastText)
    }

    /**
     * The keyboard is about to put in text the user did not type here: a paste,
     * a clipboard or snippet insert, an AI apply. Such a replace may first empty
     * the field, which must not read as a send either.
     */
    fun markInserted(nowMillis: Long) {
        insertMarkUntil = nowMillis + MARK_WINDOW_MS
        deleteMarkUntil = nowMillis + MARK_WINDOW_MS
    }

    /** The user is deleting (backspace or cut); an emptied field is not a send. */
    fun markOwnDelete(nowMillis: Long) { deleteMarkUntil = nowMillis + MARK_WINDOW_MS }

    /**
     * The text before the cursor after any edit. Returns a candidate when the
     * app has just cleared a field holding a mostly-typed message.
     *
     * [fieldIsEmpty] is asked only when the text before the cursor has just
     * become empty, to tell "the app cleared the field" apart from "the cursor
     * moved to the start". That keeps the extra editor read off normal keystrokes.
     */
    fun onText(text: String, nowMillis: Long, fieldIsEmpty: () -> Boolean = { true }): Candidate? {
        val previous = lastText
        lastText = text
        val inserting = nowMillis <= insertMarkUntil
        val ownDelete = nowMillis <= deleteMarkUntil
        val delta = text.length - previous.length
        if (delta > 0 && inserting) insertMarkUntil = Long.MIN_VALUE
        if (delta < 0 && ownDelete && text.isNotEmpty() && !inserting) deleteMarkUntil = Long.MIN_VALUE
        when {
            delta > 0 && (inserting || delta > maxTypedJump) -> inserted += delta
            delta > 0 -> typed += delta
            text.isEmpty() && previous.isNotBlank() -> {
                if (!fieldIsEmpty()) {
                    // Only the cursor moved to the start; nothing was sent.
                    lastText = previous
                    return null
                }
                // The field emptied in one step. Unless the user deleted it here,
                // the app took the text: it was sent.
                val candidate = if (!ownDelete && previous.length > 1) candidateFor(previous, nowMillis) else null
                deleteMarkUntil = Long.MIN_VALUE
                resetCounts("")
                return candidate
            }
            delta < 0 -> {
                // Backspace removes from whichever kind was typed last; keep the share.
                val total = typed + inserted
                if (total > 0) {
                    val keep = text.length.toDouble() / total
                    typed = (typed * keep).toInt()
                    inserted = (inserted * keep).toInt()
                }
            }
        }
        return null
    }

    /** The keyboard's own Send action, with the draft as it is right before sending. */
    fun onSend(draft: String, nowMillis: Long): Candidate? {
        val candidate = candidateFor(draft, nowMillis)
        // The app will now clear the field; that clear must not count a second time.
        // resetCounts makes the whole draft "inserted", so the clear cannot qualify.
        resetCounts(draft)
        return candidate
    }

    private fun candidateFor(text: String, nowMillis: Long): Candidate? {
        if (!gate.allowsCapture) return null
        val trimmed = text.trim()
        if (trimmed.length < MIN_CHARS || trimmed.none { it.isLetterOrDigit() || Character.isSurrogate(it) }) return null
        val total = typed + inserted
        if (total <= 0 || typed.toDouble() / total < minOwnShare) return null
        return Candidate(trimmed, packageName, nowMillis)
    }

    private fun resetCounts(current: String) {
        typed = 0
        inserted = current.length
    }

    companion object {
        /** A key, swipe word, suggestion or auto-fix adds at most about one word. */
        const val MAX_TYPED_JUMP = 24
        /** At least this share of the text must have been typed here. */
        const val MIN_OWN_SHARE = 0.8
        const val MIN_CHARS = 2
        const val MARK_WINDOW_MS = 750L
    }
}

/**
 * KEYBOARD-024: the keyboard normally reads only the last 1,000 characters before
 * the cursor. For the twin, a long message must not be cut, so when that window is
 * full the twin reads a larger one ([CHARS], twice the 4,000-character per-message
 * cap so redaction never works on a cut-off value). Short messages, the common case,
 * cost no extra editor read.
 */
object TwinTextWindow {
    const val CHARS = 8_000

    fun expand(window: String, windowLimit: Int, read: (Int) -> CharSequence?): String {
        if (window.length < windowLimit) return window
        val larger = runCatching { read(CHARS)?.toString() }.getOrNull() ?: return window
        return if (larger.length >= window.length) larger else window
    }
}
