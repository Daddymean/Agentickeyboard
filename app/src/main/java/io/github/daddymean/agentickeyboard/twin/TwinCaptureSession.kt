package io.github.daddymean.agentickeyboard.twin

/**
 * KEYBOARD-024: decides, per editor, when a draft counts as one of Keith's own sent
 * messages for the legacy twin. Conservative by design (security review on
 * 19c2dd0, r4238090902 / r4238090908 / r4238090918): **when unsure, nothing is saved.**
 *
 * **Only keyboard-typed text, all or nothing.** The session keeps a model of the
 * draft ([doc]) built only from edits the keyboard itself made: key, swipe and
 * suggestion commits and keyboard deletes, reported through [noteKeyboardCommit] and
 * [noteKeyboardDelete] (the service wraps its InputConnection to do this). Any
 * text that enters the field another way taints the whole draft: a pre-filled
 * field, a paste, a clipboard, snippet or AI insert ([markInserted]), or any change
 * the keyboard cannot explain. A tainted draft is never saved, not even its
 * typed parts, and stays tainted until the field is empty again. Dropping is
 * chosen over keeping "only the typed spans" because the keyboard sees just the
 * text before the cursor: it cannot reliably place spans after selection
 * replacements or app-side edits, and typed fragments of a reply built around
 * someone else's words can still carry those words' meaning.
 *
 * **Caret and selection movement never count as typing.** Growth of the text before
 * the cursor is typing only when the keyboard has just committed that much text.
 * Moving the caret over text already in the model changes nothing, and any other
 * growth taints the draft.
 *
 * **A save needs a strong send signal.**
 * - The keyboard's Send or Go action ([onSend]): the field text must exactly equal
 *   the typed model, so hidden app-side edits or replaced selections drop it.
 * - Otherwise only an app-side clear in a chat-type field (most chat apps send with
 *   their own button), and only when nothing makes it look like a deletion: no
 *   selection seen since the last typing, no keyboard delete, cut or select-all
 *   just before, no unexplained shrink, the caret at the end and the field truly
 *   empty. A selection followed by a cut, or a clear-all, never saves.
 *
 * Pure logic with no Android types; it runs on the main thread with string
 * comparisons on the draft only. Sanitising, encrypting and writing happen
 * elsewhere, off the main thread.
 */
class TwinCaptureSession {
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
    private var chatField = false
    private var gate: Gate = Gate(twinEnabled = false, twinPaused = false, learningPaused = false, sensitiveField = true)

    /** The draft as built from keyboard edits; the caret sits at [prefix].length. */
    private var doc = ""
    private var prefix = ""
    /** Some of the draft did not come from keyboard typing: never save it. */
    private var tainted = false
    /** Something made an app-side clear look like a deletion: no clear-as-send. */
    private var clearUnsafe = false

    private var credit = 0
    private var creditUntil = Long.MIN_VALUE
    private var deleteCredit = 0
    private var deleteUntil = Long.MIN_VALUE

    /**
     * A new field gained focus. Whatever it already holds was not typed here.
     * [chatField] is the service's chat-app heuristic for allowing clear-as-send.
     */
    fun start(packageName: String?, initialText: String, gate: Gate, chatField: Boolean = false) {
        this.packageName = packageName
        this.chatField = chatField
        this.gate = gate
        reset(initialText)
        tainted = initialText.isNotEmpty()
    }

    /** Settings or field state changed mid-session. A closed gate drops the current draft. */
    fun updateGate(gate: Gate) {
        this.gate = gate
        if (!gate.allowsCapture) tainted = true
    }

    /** The keyboard committed [text] from a key, swipe word or suggestion. */
    fun noteKeyboardCommit(text: CharSequence, nowMillis: Long) {
        credit = if (nowMillis <= creditUntil) credit + text.length else text.length
        creditUntil = nowMillis + MARK_WINDOW_MS
    }

    /** The keyboard deleted [count] characters before the caret (backspace or an auto-fix replacement). */
    fun noteKeyboardDelete(nowMillis: Long, count: Int = 1) {
        deleteCredit = if (nowMillis <= deleteUntil) deleteCredit + count else count
        deleteUntil = nowMillis + MARK_WINDOW_MS
    }

    /** Text the user did not type here is entering the field: paste, clipboard, snippet, AI. */
    fun markInserted(@Suppress("UNUSED_PARAMETER") nowMillis: Long) {
        tainted = true
        clearUnsafe = true
    }

    /** The user cut or selected everything through the keyboard: an emptied field is a deletion. */
    fun markOwnDelete(nowMillis: Long) {
        deleteUntil = nowMillis + MARK_WINDOW_MS
        clearUnsafe = true
    }

    /**
     * Called on every editor update with the text before the cursor (or before the
     * selection start) and whether a selection is active. Returns a candidate only
     * for a safe app-side clear in a chat field.
     *
     * [fieldIsEmpty] is asked only when the text before the cursor has just become
     * empty, so the extra editor read stays off normal keystrokes.
     */
    fun onText(
        text: String,
        nowMillis: Long,
        selectionActive: Boolean = false,
        fieldIsEmpty: () -> Boolean = { true }
    ): Candidate? {
        val old = prefix
        if (selectionActive) clearUnsafe = true

        // The field emptied (checked even when the prefix did not change, so a
        // select-all then cut still resets the draft).
        if (text.isEmpty() && doc.isNotEmpty() && !selectionActive && fieldIsEmpty()) {
            val candidate = clearCandidate(old, nowMillis)
            reset("")
            return candidate
        }
        if (text == old) return null

        val liveCredit = if (nowMillis <= creditUntil) credit else 0
        val liveDeletes = if (nowMillis <= deleteUntil) deleteCredit else 0
        val common = commonPrefixLength(text, old)
        val removed = old.length - common
        val added = text.length - common
        when {
            // The keyboard's own edit at the caret: exactly what it just deleted and
            // committed (a key, a backspace, or an auto-fix delete-then-commit).
            (removed > 0 || added > 0) && removed <= liveDeletes && added <= liveCredit -> {
                credit = liveCredit - added
                deleteCredit = liveDeletes - removed
                doc = text + doc.drop(old.length)
            }
            // Caret (or selection start) moved over text already in the model. Never typing.
            doc.startsWith(text) -> {
                // A shorter prefix may also be the app deleting the end; a clear after
                // that is not trusted.
                if (text.length < old.length) clearUnsafe = true
            }
            // Anything else entered the field some other way.
            else -> {
                tainted = true
                clearUnsafe = true
                doc = text
            }
        }
        prefix = text
        return null
    }

    /**
     * The keyboard's Send or Go action, with the whole field text ([fieldText] is the
     * text before plus after the cursor) right before sending.
     */
    fun onSend(fieldText: String, nowMillis: Long): Candidate? {
        val candidate = if (fieldText == doc) candidateFor(doc, nowMillis) else null
        // Whatever the app does with the field next must not count a second time.
        tainted = true
        clearUnsafe = true
        return candidate
    }

    private fun clearCandidate(prefixBeforeClear: String, nowMillis: Long): Candidate? {
        if (!chatField || clearUnsafe) return null
        if (nowMillis <= deleteUntil) return null
        if (prefixBeforeClear != doc) return null // the caret was not at the end
        return candidateFor(doc, nowMillis)
    }

    private fun candidateFor(text: String, nowMillis: Long): Candidate? {
        if (!gate.allowsCapture || tainted) return null
        val trimmed = text.trim()
        if (trimmed.length < MIN_CHARS || trimmed.none { it.isLetterOrDigit() || Character.isSurrogate(it) }) return null
        return Candidate(trimmed, packageName, nowMillis)
    }

    private fun reset(current: String) {
        doc = current
        prefix = current
        tainted = false
        clearUnsafe = false
        credit = 0
        creditUntil = Long.MIN_VALUE
        deleteCredit = 0
        deleteUntil = Long.MIN_VALUE
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    companion object {
        const val MIN_CHARS = 2
        /** How long a keyboard commit or delete explains the next editor update. */
        const val MARK_WINDOW_MS = 750L

        /**
         * The chat-app heuristic for clear-as-send: a listed chat app, a plain text
         * field (not a URI, e-mail, filter, password or number field) and no Search action.
         */
        fun isChatField(packageName: String?, inputType: Int, imeOptions: Int): Boolean {
            if (packageName == null || packageName !in CHAT_PACKAGES) return false
            if (inputType and android.text.InputType.TYPE_MASK_CLASS != android.text.InputType.TYPE_CLASS_TEXT) return false
            val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
            val allowed = setOf(
                android.text.InputType.TYPE_TEXT_VARIATION_NORMAL,
                android.text.InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
                android.text.InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
            )
            if (variation !in allowed) return false
            val action = imeOptions and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
            return action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        }

        /**
         * Chat apps whose compose box is cleared by the app's own send button. Only
         * these may count an app-side clear as a send.
         */
        val CHAT_PACKAGES = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging",
            "com.samsung.android.messaging", "org.telegram.messenger", "org.thoughtcrime.securesms",
            "com.facebook.orca", "com.instagram.android", "com.discord", "com.Slack",
            "com.snapchat.android", "com.microsoft.teams", "jp.naver.line.android", "com.viber.voip",
            "com.tencent.mm", "com.google.android.apps.dynamite", "com.kakao.talk", "com.textra",
            "com.android.mms", "com.google.android.apps.googlevoice", "com.groupme.android",
            "com.skype.raider"
        )
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
