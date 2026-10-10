package io.github.daddymean.agentickeyboard.twin

import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression traces for the three P1 findings of the exact-head security review on
 * 19c2dd0 (#168 r4238090902, r4238090908, r4238090918). Each one failed on 19c2dd0
 * (with that version's API) before the capture rules were rewritten.
 */
class TwinCaptureRegressionTest {

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
}
