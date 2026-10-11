package io.github.daddymean.agentickeyboard.twin

import android.text.InputType
import android.view.inputmethod.EditorInfo
import io.github.daddymean.agentickeyboard.util.EditorPrivacy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TwinCaptureSessionTest {
    private val open = TwinTyping.OPEN

    @Test
    fun typedMessageClearedByAChatAppIsCaptured() {
        val t = TwinTyping.chat()
        t.type("See you at six, love you")
        val c = t.appClears()!!
        assertEquals("See you at six, love you", c.text)
        assertEquals("com.whatsapp", c.packageName)
    }

    @Test
    fun sendActionCapturesOnceAndTheFollowingClearIsNotADuplicate() {
        val t = TwinTyping.chat()
        t.type("On my way")
        assertEquals("On my way", t.send()?.text)
        assertNull(t.appClears())
        // The next message in the same field is learned again.
        t.type("Here now")
        assertEquals("Here now", t.send()?.text)
    }

    @Test
    fun sendWorksOutsideChatAppsButAClearDoesNot() {
        val notChat = TwinTyping.chat(chatField = false)
        notChat.type("Meeting notes for Monday")
        assertNull(notChat.appClears())
        val viaSend = TwinTyping.chat(chatField = false)
        viaSend.type("Meeting notes for Monday")
        assertEquals("Meeting notes for Monday", viaSend.send()?.text)
    }

    @Test
    fun typosFixedWithBackspaceAndCaretMovesStillSaveExactText() {
        val t = TwinTyping.chat()
        t.type("Helo")
        t.backspace(); t.backspace()
        t.type("llo there")
        t.caretTo(3); t.caretTo(t.field.length)
        assertEquals("Hello there", t.send()?.text)
    }

    @Test
    fun nothingIsCapturedInPasswordOrIncognitoFields() {
        val password = EditorPrivacy.isSensitiveEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0)
        val incognito = EditorPrivacy.isSensitiveEditor(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(password && incognito)
        for (sensitive in listOf(password, incognito)) {
            val t = TwinTyping.chat(gate = open.copy(sensitiveField = sensitive))
            t.type("hunter2 secret words")
            assertNull(t.send())
            val c = TwinTyping.chat(gate = open.copy(sensitiveField = sensitive))
            c.type("hunter2 secret words")
            assertNull(c.appClears())
        }
    }

    @Test
    fun nothingIsCapturedWhilePausedOrOff() {
        for (gate in listOf(open.copy(learningPaused = true), open.copy(twinPaused = true), open.copy(twinEnabled = false))) {
            val t = TwinTyping.chat(gate = gate)
            t.type("private thoughts here")
            assertNull(t.send())
        }
    }

    @Test
    fun pausingMidMessageDropsTheWholeDraft() {
        val t = TwinTyping.chat()
        t.type("first half typed ")
        t.session.updateGate(open.copy(learningPaused = true))
        t.session.updateGate(open)
        t.type("then more")
        assertNull(t.send())
    }

    @Test
    fun anyInsertedTextDropsTheWholeMessage() {
        // Pre-filled by the app.
        val prefilled = TwinTyping.chat(initial = "Text the other person wrote")
        prefilled.type(" ok")
        assertNull(prefilled.send())
        // A paste in one step, without any keyboard mark.
        val pasted = TwinTyping.chat()
        pasted.type("look: ")
        pasted.external(pasted.field + "a long pasted paragraph")
        pasted.type(" wow")
        assertNull(pasted.send())
        // A marked insert (clipboard, snippet or AI apply), even if short.
        val ai = TwinTyping.chat()
        ai.type("hey can u come")
        ai.session.markInserted(ai.now)
        ai.external("Hey, can you come over?")
        assertNull(ai.send())
        assertNull(ai.appClears())
    }

    @Test
    fun sendWhenTheFieldDiffersFromWhatWasTypedIsDropped() {
        // The app put text after the caret, which the keyboard never saw being typed.
        val t = TwinTyping.chat()
        t.type("my reply")
        assertNull(t.session.onSend("my reply\n> their quoted text", t.now + 10))
    }

    @Test
    fun clearsThatLookLikeDeletionsNeverSave() {
        // Backspacing everything.
        val back = TwinTyping.chat()
        back.type("never mind")
        while (back.field.isNotEmpty()) assertNull(back.backspace())
        // Keyboard select-all / cut, then the clear.
        val cut = TwinTyping.chat()
        cut.type("on second thought")
        cut.session.markOwnDelete(cut.now)
        assertNull(cut.appClears())
        // A selection seen earlier in the draft (for example the start of a host cut).
        val sel = TwinTyping.chat()
        sel.type("delete this part")
        sel.external(sel.field, selectionActive = true, caret = 7)
        sel.caretTo(sel.field.length)
        assertNull(sel.appClears())
        // The caret was not at the end when the field emptied (a host clear-all button).
        val mid = TwinTyping.chat()
        mid.type("half written")
        mid.caretTo(4)
        assertNull(mid.appClears())
    }

    @Test
    fun cursorMovedToStartIsNotAClear() {
        val t = TwinTyping.chat()
        t.type("still writing this")
        assertNull(t.session.onText("", t.now + 300) { false })
        t.caretTo(t.field.length)
        assertEquals("still writing this", t.send()?.text)
    }

    @Test
    fun blankAndOneCharacterMessagesAreIgnored() {
        val t = TwinTyping.chat()
        t.type("k")
        assertNull(t.send())
    }

    @Test
    fun chatFieldHeuristic() {
        val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE
        assertTrue(TwinCaptureSession.isChatField("com.whatsapp", text, EditorInfo.IME_ACTION_SEND))
        assertFalse(TwinCaptureSession.isChatField("com.android.chrome", text, 0))
        assertFalse(TwinCaptureSession.isChatField("com.whatsapp", text, EditorInfo.IME_ACTION_SEARCH))
        assertFalse(TwinCaptureSession.isChatField("com.whatsapp", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0))
        assertFalse(TwinCaptureSession.isChatField("com.whatsapp", InputType.TYPE_CLASS_NUMBER, 0))
        assertFalse(TwinCaptureSession.isChatField(null, text, 0))
    }
}
