package io.github.daddymean.agentickeyboard.provenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OriginLedgerTest {

    private fun ledger(initialCaret: Int = 0) = OriginLedger().apply { begin(initialCaret, initialCaret, 0L) }

    private fun OriginLedger.type(s: String, start: Long = 0L) {
        s.forEachIndexed { i, c -> commit(c.toString(), Origin.TYPED, 1, start + i * 150L) }
    }

    @Test
    fun typedTextIsTaggedTyped() {
        val l = ledger()
        l.type("hello")
        assertEquals("hello", l.textSnapshot())
        assertEquals(5, l.counts()[Origin.TYPED])
        assertEquals(5, l.selStart)
    }

    @Test
    fun aiTextReplacesTheSelectionAndKeepsItsTag() {
        val l = ledger()
        l.type("hi there")
        l.select(0, 8)
        l.commit("Hello, friend.", Origin.AI, 1, 2_000L)
        assertEquals("Hello, friend.", l.textSnapshot())
        assertEquals(mapOf(Origin.AI to 14), l.counts().filterValues { it > 0 })
    }

    @Test
    fun backspaceAndForwardDeleteRemoveOneCodePoint() {
        val l = ledger()
        l.type("ab")
        l.commit("😀", Origin.TYPED, 1, 500L) // one emoji, two UTF-16 units
        l.deleteKey(forward = false)
        assertEquals("ab", l.textSnapshot())
        l.select(0, 0)
        l.deleteKey(forward = true)
        assertEquals("b", l.textSnapshot())
        assertEquals(0, l.selStart)
    }

    @Test
    fun deleteSurroundingKeepsTheSelection() {
        val l = ledger()
        l.type("abcdef")
        l.select(2, 4) // "cd"
        l.deleteSurrounding(1, 1)
        assertEquals("acdf", l.textSnapshot())
        assertEquals(1, l.selStart)
        assertEquals(3, l.selEnd)
    }

    @Test
    fun textAlreadyInTheFieldIsUnwitnessedOnceSeen() {
        val l = ledger(initialCaret = 6)
        l.resyncBefore("Dear  ") // the editor's real prefix
        l.type("Sam")
        assertEquals("Dear  Sam", l.textSnapshot())
        assertEquals(6, l.counts()[Origin.UNWITNESSED])
        assertEquals(3, l.counts()[Origin.TYPED])
    }

    @Test
    fun anAppSideEditBecomesUnwitnessed() {
        val l = ledger()
        l.type("hi @jo")
        // The app's mention picker replaced "@jo" with "@Jordan Lee " behind the keyboard's back.
        l.select(15, 15)
        l.resyncBefore("hi @Jordan Lee ")
        assertEquals("hi @Jordan Lee ", l.textSnapshot())
        val counts = l.counts()
        assertEquals(4, counts[Origin.TYPED]) // "hi @"
        assertEquals(11, counts[Origin.UNWITNESSED])
    }

    @Test
    fun reconcileMarksWhatTheKeyboardDidNotWriteUnwitnessed() {
        val l = ledger()
        l.type("first second")
        l.select(0, 0)
        l.reconcile("first SECOND, and more")
        assertEquals("first SECOND, and more", l.textSnapshot())
        assertEquals(6, l.counts()[Origin.TYPED]) // "first "
        assertEquals(16, l.counts()[Origin.UNWITNESSED])
    }

    @Test
    fun anEditorPasteInTheMiddleKeepsTheTextAfterIt() {
        val l = ledger()
        l.type("first second")
        l.select(5, 5)
        l.unknownInsertAtSelection() // the keyboard's Paste action; the editor inserts ", pasted"
        l.select(13, 13) // onUpdateSelection
        l.resyncBefore("first, pasted")
        l.reconcile("first, pasted second")
        assertEquals("first, pasted second", l.textSnapshot())
        assertEquals(12, l.counts()[Origin.TYPED])
        assertEquals(8, l.counts()[Origin.UNWITNESSED])
        assertEquals(Origin.TYPED, l.originAt(13)) // the space before "second"
    }

    @Test
    fun labelledBlocksOverrideTheDefaultOrigin() {
        val l = ledger()
        assertEquals(Origin.INSERTED, l.originFor(Origin.INSERTED))
        l.labelled(Origin.PASTED) { assertEquals(Origin.PASTED, l.originFor(Origin.INSERTED)) }
        assertEquals(Origin.TYPED, l.originFor(Origin.TYPED))
    }

    @Test
    fun overflowStopsRecordingAndDropsTheText() {
        val l = OriginLedger(maxChars = 10).apply { begin(0, 0, 0L) }
        l.type("0123456789")
        assertFalse(l.overflowed)
        l.commit("x", Origin.TYPED, 1, 0L)
        assertTrue(l.overflowed)
        assertFalse(l.active)
        assertEquals(0, l.length)
        l.resyncBefore("anything") // inactive: no-op, no crash
        assertEquals(0, l.length)
    }

    @Test
    fun inactiveLedgerRecordsNothing() {
        val l = OriginLedger()
        l.commit("abc", Origin.TYPED, 1, 0L)
        assertEquals(0, l.length)
    }

    /**
     * Random keyboard edits interleaved with app-side edits the keyboard never sees.
     * After every onUpdateSelection-style resync and a final reconcile:
     * the model text equals the editor's, no origin ever has more characters than the
     * keyboard inserted with it, and with no app-side edits the tags are exact.
     */
    @Test
    fun randomEditSequencesNeverInflateAnOrigin() {
        repeat(400) { seed ->
            val rnd = Random(seed)
            val externalEdits = seed % 2 == 0
            val editor = FakeEditor()
            val l = OriginLedger()
            l.begin(0, 0, 0L)
            val inserted = IntArray(Origin.entries.size)
            repeat(120) { step ->
                when (rnd.nextInt(10)) {
                    in 0..4 -> {
                        val c = "abc de".random(rnd).toString()
                        editor.commit(c, Origin.TYPED); l.commit(c, Origin.TYPED, 1, step * 100L)
                        inserted[Origin.TYPED.ordinal]++
                    }
                    5 -> {
                        val origin = listOf(Origin.AI, Origin.PASTED, Origin.SWIPED, Origin.ASSISTED).random(rnd)
                        val s = "word${rnd.nextInt(9)} "
                        editor.commit(s, origin); l.commit(s, origin, 1, step * 100L)
                        inserted[origin.ordinal] += s.length
                    }
                    6 -> { editor.backspace(); l.deleteKey(forward = false) }
                    7 -> {
                        val a = rnd.nextInt(editor.text.length + 1); val b = rnd.nextInt(editor.text.length + 1)
                        editor.select(minOf(a, b), maxOf(a, b)); l.select(minOf(a, b), maxOf(a, b))
                    }
                    8 -> if (externalEdits) editor.externalEdit(rnd)
                    9 -> {
                        val n = rnd.nextInt(3); editor.deleteSurrounding(n, 0); l.deleteSurrounding(n, 0)
                    }
                }
                // onUpdateSelection: the service reports the caret, then the read before it.
                l.select(editor.selStart, editor.selEnd)
                l.resyncBefore(editor.text.substring(0, editor.selStart))
            }
            l.reconcile(editor.text)
            assertEquals("seed $seed", editor.text, l.textSnapshot())
            val counts = l.counts()
            for (o in Origin.entries) {
                if (o == Origin.UNWITNESSED) continue
                assertTrue("seed $seed: $o ${counts[o]} > ${inserted[o.ordinal]}", counts.getValue(o) <= inserted[o.ordinal])
            }
            if (!externalEdits) {
                for (i in editor.text.indices) assertEquals("seed $seed at $i", editor.tags[i], l.originAt(i))
            }
        }
    }

    /** The editor's real behaviour, with the true origin of every character. */
    private class FakeEditor {
        val text = StringBuilder()
        val tags = ArrayList<Origin>()
        var selStart = 0
        var selEnd = 0

        fun commit(s: String, origin: Origin) {
            text.replace(selStart, selEnd, s)
            repeat(selEnd - selStart) { tags.removeAt(selStart) }
            tags.addAll(selStart, List(s.length) { origin })
            selStart += s.length; selEnd = selStart
        }

        fun backspace() {
            if (selEnd > selStart) { remove(selStart, selEnd); selEnd = selStart }
            else if (selStart > 0) { remove(selStart - 1, selStart); selStart--; selEnd-- }
        }

        fun deleteSurrounding(before: Int, after: Int) {
            val a = minOf(after, text.length - selEnd); if (a > 0) remove(selEnd, selEnd + a)
            val b = minOf(before, selStart); if (b > 0) { remove(selStart - b, selStart); selStart -= b; selEnd -= b }
        }

        fun select(a: Int, b: Int) { selStart = a; selEnd = b }

        fun externalEdit(rnd: Random) {
            val at = rnd.nextInt(text.length + 1)
            if (rnd.nextBoolean() || text.isEmpty()) {
                val s = "@x"; text.insert(at, s); tags.addAll(at, List(s.length) { Origin.UNWITNESSED })
                if (selStart >= at) selStart += s.length
                if (selEnd >= at) selEnd += s.length
            } else {
                val end = minOf(text.length, at + 2); val from = minOf(at, end)
                remove(from, end)
                selStart = adjust(selStart, from, end); selEnd = adjust(selEnd, from, end)
            }
        }

        private fun adjust(p: Int, from: Int, end: Int) = if (p >= end) p - (end - from) else minOf(p, from)

        private fun remove(from: Int, end: Int) {
            text.delete(from, end); repeat(end - from) { tags.removeAt(from) }
        }
    }
}
