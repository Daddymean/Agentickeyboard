package io.github.daddymean.agentickeyboard.twin

/** Test helper: drives a [TwinCaptureSession] the way the service does. */
class TwinTyping(val session: TwinCaptureSession, var now: Long = 1_000L) {
    var field = ""
        private set

    /** Key presses: the keyboard commits each character, then the editor reports. */
    fun type(text: String): TwinCaptureSession.Candidate? {
        var last: TwinCaptureSession.Candidate? = null
        for (ch in text) {
            now += 40
            session.noteKeyboardCommit(ch.toString(), now)
            field += ch
            last = session.onText(field, now + 5) ?: last
        }
        return last
    }

    fun backspace(): TwinCaptureSession.Candidate? {
        now += 40
        session.noteKeyboardDelete(now)
        field = field.dropLast(1)
        return session.onText(field, now + 5)
    }

    /** The editor reports a new caret position (no edit by anyone). */
    fun caretTo(position: Int): TwinCaptureSession.Candidate? {
        now += 300
        return session.onText(field.take(position), now) { position == 0 && field.isEmpty() }
    }

    /** Something outside the keyboard changed the field. */
    fun external(newField: String, selectionActive: Boolean = false, caret: Int = newField.length): TwinCaptureSession.Candidate? {
        now += 300
        field = newField
        return session.onText(newField.take(caret), now, selectionActive) { newField.isEmpty() }
    }

    fun send(): TwinCaptureSession.Candidate? {
        now += 40
        return session.onSend(field, now)
    }

    /** The app cleared the field (its own send button, a clear button or a cut). */
    fun appClears(): TwinCaptureSession.Candidate? = external("")

    companion object {
        val OPEN = TwinCaptureSession.Gate(twinEnabled = true, twinPaused = false, learningPaused = false, sensitiveField = false)

        /** A field whose text after the caret is [after] (null: unreadable) and selection state [hasSelection]. */
        fun chat(
            initial: String = "",
            gate: TwinCaptureSession.Gate = OPEN,
            chatField: Boolean = true,
            after: String? = "",
            hasSelection: Boolean? = false
        ) =
            TwinTyping(TwinCaptureSession().apply { start("com.whatsapp", initial, gate, chatField, textAfter = after, hasSelection = hasSelection) }).also {
                if (initial.isNotEmpty()) it.setField(initial)
            }
    }

    internal fun setField(text: String) { field = text }
}
