package io.github.daddymean.agentickeyboard.twin

import android.view.inputmethod.InputConnection
import io.github.daddymean.agentickeyboard.util.commitInserted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

/**
 * Regression traces for the three P1 findings of the exact-head security review on
 * 19c2dd0 (#168 r4238090902, r4238090908, r4238090918). Each one failed on 19c2dd0
 * (with that version's API) before the capture rules were rewritten.
 *
 * Plus the two P1 findings of the re-review on 90f8f3e (r4238828510, r4238828515):
 * both tests failed on 90f8f3e with that version's API (clipboard: saved "Text
 * somebody else wrote, copied from another app"; suffix: saved "ok sounds good").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwinCaptureRegressionTest {
    private val base = Proxy.newProxyInstance(InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)) { _, m, _ ->
        when (m.returnType) { java.lang.Boolean.TYPE -> true; Integer.TYPE -> 0; else -> null }
    } as InputConnection

    private fun emptyChat() = TwinCaptureSession().apply {
        start("com.whatsapp", "", TwinTyping.OPEN, chatField = true, textAfter = "", hasSelection = false)
    }

    /** r4238090902: 10 pasted + 40 typed characters must not be saved, in whole or part. */
    @Test
    fun paste10ThenType40IsNeverSaved() {
        val t = TwinTyping.chat()
        t.session.markInserted(t.now)
        t.external("PASTED1234")
        t.type("t".repeat(40))
        assertNull(t.send())
        val again = TwinTyping.chat()
        again.session.markInserted(again.now)
        again.external("PASTED1234")
        again.type("t".repeat(40))
        assertNull(again.appClears())
    }

    /** r4238090908: caret left/right cycles over pre-filled text never count as typing. */
    @Test
    fun prefillThenCaretLeftRightCyclesIsNeverSaved() {
        val prefill = "Quoted words from somebody else."
        val t = TwinTyping.chat(initial = prefill)
        repeat(50) {
            t.caretTo(prefill.length - 10)
            t.caretTo(prefill.length)
        }
        assertNull(t.send())
        val cleared = TwinTyping.chat(initial = prefill)
        repeat(50) { cleared.caretTo(0); cleared.caretTo(prefill.length) }
        cleared.type(" ok")
        assertNull(cleared.appClears())
    }

    /** r4238090918: type, then the host app's Select all and Cut, must not be saved as sent. */
    @Test
    fun typeThenHostSelectAllCutIsNeverSaved() {
        val t = TwinTyping.chat()
        t.type("draft I decided not to send")
        // Host Select all: a selection from 0, so the text before it is empty but the field is not.
        t.external(t.field, selectionActive = true, caret = 0)
        // Host Cut: the field is now empty.
        assertNull(t.appClears())
    }

    /**
     * r4238828510: the Clipboard Intelligence panel's Paste chip. Its text now goes
     * through onInsertText (commitInserted); a stray multi-character commit through
     * the plain typed-key path is an insertion too. Neither may be saved on Send.
     */
    @Test
    fun clipboardPanelPasteThenSendIsNeverSaved() {
        val clip = "Text somebody else wrote, copied from another app"
        // The dedicated insertion path the chip now uses.
        val session = emptyChat()
        var now = 1_000L
        val conn = TwinTrackingInputConnection(base, session) { now }
        conn.commitInserted(clip, 1)
        now += 5; session.onText(clip, now)
        assertNull(session.onSend(clip, now + 40))
        // The 90f8f3e route (onKeyPress -> commitText) with the same payload: multi-character
        // commits default to inserted, so even a missed call site cannot count as typing.
        val old = emptyChat()
        val oldConn = TwinTrackingInputConnection(base, old) { now }
        oldConn.commitText(clip, 1)
        now += 5; old.onText(clip, now)
        assertNull(old.onSend(clip, now + 40))
        // Typing more after the paste does not rescue it.
        val more = TwinTyping(emptyChat())
        TwinTrackingInputConnection(base, more.session) { more.now }.commitText(clip, 1)
        more.external(clip)
        more.type(" and a long typed reply of my own words")
        assertNull(more.send())
        // The chip in the layout really is wired to the insertion callback.
        val layout = TwinInsertionPathGuardTest.layoutSource()
        val chip = layout.lines().single { "Clipboard Intelligence Paste chip" in it }
        assertTrue(chip, "onInsertText(clipboardText" in chip)
    }

    /**
     * r4238828515: the field opens with text after the caret ("|> quoted text").
     * The typed prefix plus an app-side clear must not be saved.
     */
    @Test
    fun prefilledSuffixAfterCaretThenTypeAndClearIsNeverSaved() {
        val suffix = "> quoted text from them"
        // Session start now reports the text after the caret: it taints the draft.
        val t = TwinTyping.chat(after = suffix.take(1))
        typeBeforeSuffix(t, "ok sounds good ")
        assertNull(t.appClears())

        // State unknown at start (the editor would not say what follows the caret):
        // clear-as-send is off for the session, so an app clear never saves...
        val unknown = TwinTyping.chat(after = null)
        unknown.type("ok sounds good ")
        assertTrue(unknown.session.clearCaptureDisabled)
        assertNull(unknown.appClears())
        // ...and stays off after the field empties, while Send/Go with its full-field check still works.
        unknown.type("next one")
        assertNull(unknown.appClears())
        unknown.type("typed and sent")
        assertEquals("typed and sent", unknown.send()?.text)

        // Unknown selection state counts as unknown too; a selection at start taints.
        assertTrue(TwinTyping.chat(hasSelection = null).session.clearCaptureDisabled)
        val sel = TwinTyping.chat(hasSelection = true)
        sel.type("ok sounds good")
        assertNull(sel.send())

        // A suffix that appears later (after a reset) is caught when the next draft starts.
        val later = TwinTyping.chat()
        typeBeforeSuffix(later, "ok sounds good ")
        assertNull(later.appClears())
        // ...and an editor that cannot say what follows the caret taints the new draft too.
        val noAnswer = TwinTyping.chat()
        typeBeforeSuffix(noAnswer, "ok sounds good ", answer = null)
        assertNull(noAnswer.appClears())
    }

    /** Types [text] while text sits after the caret; the editor answers [answer] when asked. */
    private fun typeBeforeSuffix(t: TwinTyping, text: String, answer: Boolean? = false) {
        var field = ""
        for (ch in text) {
            t.now += 40
            t.session.noteKeyboardCommit(ch.toString(), t.now)
            field += ch
            t.session.onText(field, t.now + 5) { answer }
        }
        t.setField(field)
    }

    /**
     * Codex review on 08efa93 (r4239970466): an app-side substitution inside the credit
     * window, or a commit the editor rejected, must not pass as typing just because the
     * lengths fit. Failed on 08efa93 (saved "Xhello").
     */
    @Test
    fun substitutedOrRejectedCommitIsNeverSaved() {
        // The keyboard commits "a", but the app puts "X" there instead.
        val sub = TwinTyping(emptyChat())
        sub.session.noteKeyboardCommit("a", sub.now)
        sub.external("X")
        sub.type("hello")
        assertNull(sub.send())

        // The editor rejects the commit; the app inserts "X" in the same window.
        val rejecting = Proxy.newProxyInstance(InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)) { _, m, _ ->
            when (m.returnType) { java.lang.Boolean.TYPE -> false; Integer.TYPE -> 0; else -> null }
        } as InputConnection
        val rej = TwinTyping(emptyChat())
        TwinTrackingInputConnection(rejecting, rej.session) { rej.now }.commitText("a", 1)
        rej.now += 5
        rej.session.onText("X", rej.now)
        rej.setField("X")
        rej.type("hello")
        assertNull(rej.send())

        // An auto-fix (delete the typed word, commit the fix) still matches by content.
        val fix = TwinTyping(emptyChat())
        fix.type("teh")
        fix.now += 40
        fix.session.noteKeyboardDelete(fix.now, 3)
        fix.session.noteKeyboardCommit("the ", fix.now)
        fix.session.onText("the ", fix.now + 5)
        fix.setField("the ")
        fix.type("end")
        assertEquals("the end", fix.send()?.text)
    }
}
