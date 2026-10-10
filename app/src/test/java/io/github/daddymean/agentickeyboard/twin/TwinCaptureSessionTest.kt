package io.github.daddymean.agentickeyboard.twin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import io.github.daddymean.agentickeyboard.util.EditorPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TwinCaptureSessionTest {
    private val open = TwinCaptureSession.Gate(twinEnabled = true, twinPaused = false, learningPaused = false, sensitiveField = false)
    private var now = 1_000L

    /** Types [text] one character at a time, as key presses arrive. */
    private fun TwinCaptureSession.type(text: String, from: String = ""): String {
        var current = from
        for (ch in text) {
            current += ch
            now += 50
            assertNull(onText(current, now))
        }
        return current
    }

    private fun session(gate: TwinCaptureSession.Gate = open, initial: String = "") =
        TwinCaptureSession().apply { start("com.whatsapp", initial, gate) }

    @Test
    fun typedMessageClearedByTheAppIsCaptured() {
        val s = session()
        val typed = s.type("See you at six, love you")
        val candidate = s.onText("", now + 10)
        assertNotNull(candidate)
        assertEquals(typed, candidate!!.text)
        assertEquals("com.whatsapp", candidate.packageName)
    }

    @Test
    fun sendActionCapturesOnceAndTheFollowingClearIsNotADuplicate() {
        val s = session()
        val typed = s.type("On my way")
        assertEquals(typed, s.onSend(typed, now)?.text)
        assertNull(s.onText("", now + 20))
    }

    @Test
    fun nothingIsCapturedInPasswordOrIncognitoFields() {
        val password = EditorPrivacy.isSensitiveEditor(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0
        )
        val incognito = EditorPrivacy.isSensitiveEditor(
            InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        )
        for (sensitive in listOf(password, incognito)) {
            val s = session(open.copy(sensitiveField = sensitive))
            val typed = s.type("hunter2 secret words")
            assertNull(s.onSend(typed, now))
            s.start("com.whatsapp", "", open.copy(sensitiveField = sensitive))
            s.type("hunter2 secret words")
            assertNull(s.onText("", now + 10))
        }
    }

    @Test
    fun nothingIsCapturedWhilePausedOrOff() {
        val closed = listOf(
            open.copy(learningPaused = true),
            open.copy(twinPaused = true),
            open.copy(twinEnabled = false)
        )
        for (gate in closed) {
            val s = session(gate)
            val typed = s.type("private thoughts here")
            assertNull(s.onSend(typed, now))
        }
    }

    @Test
    fun pausingMidMessageDropsWhatWasTypedBefore() {
        val s = session()
        val typed = s.type("first half typed ")
        s.updateGate(open.copy(learningPaused = true))
        s.updateGate(open)
        val more = s.type("then x", typed)
        assertNull(s.onSend(more, now))
    }

    @Test
    fun prefilledPastedAndInsertedTextIsNotHisTyping() {
        // Pre-filled by the app (for example a quoted reply).
        val prefilled = session(initial = "Text the other person wrote to quote")
        assertNull(prefilled.onSend(prefilled.type(" ok", "Text the other person wrote to quote"), now))

        // A large paste in one step.
        val pasted = session()
        assertNull(pasted.onText("A long pasted paragraph from somewhere else entirely", now))
        assertNull(pasted.onText("", now + 10))

        // A short marked insert (clipboard, snippet or AI apply).
        val inserted = session()
        inserted.markInserted(now)
        assertNull(inserted.onText("short clip", now + 5))
        assertNull(inserted.onText("", now + 2_000))
    }

    @Test
    fun aiReplaceThatEmptiesTheFieldFirstIsNotASend() {
        val s = session()
        s.type("hey can u come")
        s.markInserted(now)
        assertNull(s.onText("", now + 5))
        assertNull(s.onText("Hey, can you come over?", now + 10))
        // Sending the AI-written text afterwards does not count as his typing either.
        assertNull(s.onText("", now + 5_000))
    }

    @Test
    fun backspacingEverythingIsNotASend() {
        val s = session()
        var text = s.type("never mind")
        while (text.isNotEmpty()) {
            s.markOwnDelete(now)
            text = text.dropLast(1)
            now += 30
            assertNull(s.onText(text, now))
        }
    }

    @Test
    fun cursorMovedToStartIsNotASend() {
        val s = session()
        s.type("still writing this")
        assertNull(s.onText("", now + 10) { false })
    }

    @Test
    fun blankAndOneCharacterMessagesAreIgnored() {
        val s = session()
        assertNull(s.onSend(s.type("k"), now))
        assertNull(s.onSend("   ", now))
    }
}
