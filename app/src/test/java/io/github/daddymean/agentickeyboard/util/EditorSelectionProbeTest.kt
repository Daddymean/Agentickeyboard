package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorSelectionProbeTest {

    @Test
    fun collapsedCaret_isEmptyWithoutReadingTheEditor() {
        var reads = 0
        val text = EditorSelectionProbe.selectedText(5, 5) { reads++; "should not be read" }
        assertEquals("", text)
        assertEquals(0, reads)
    }

    @Test
    fun realRange_readsTheEditor() {
        var reads = 0
        assertEquals("  ", EditorSelectionProbe.selectedText(2, 4) { reads++; "  " })
        assertEquals(1, reads)
    }

    @Test
    fun unknownBounds_readTheEditor() {
        var reads = 0
        assertEquals("hi", EditorSelectionProbe.selectedText(-1, -1) { reads++; "hi" })
        assertNull(EditorSelectionProbe.selectedText(-1, 3) { reads++; null })
        assertEquals(2, reads)
    }

    /** The flags syncEditorText derives are identical to reading an empty selection. */
    @Test
    fun collapsedCaret_givesTheSameFlagsAsAnEmptyEditorAnswer() {
        for (editorAnswer in listOf<CharSequence?>(null, "")) {
            val before = editorAnswer?.toString()
            val after = EditorSelectionProbe.selectedText(7, 7) { editorAnswer }
            assertEquals(before.isNullOrBlank(), after.isNullOrBlank())
            assertEquals(before.isNullOrEmpty(), after.isNullOrEmpty())
        }
    }

    /**
     * LAB-001 measurement: synchronous InputConnection reads issued by the
     * keystroke path (onUpdateSelection -> syncEditorText) while typing a
     * sentence, with one select-word step in the middle. Counts, not time:
     * binder latency needs a phone.
     */
    @Test
    fun typingSession_halvesSelectionReads() {
        val sentence = "see you at the game tomorrow"
        // (selStart, selEnd) after each keystroke, plus one double-tap word selection.
        val updates = sentence.indices.map { it + 1 to it + 1 }.toMutableList()
        updates.add(10, 7 to 10)

        var baselineReads = 0
        var probeReads = 0
        updates.forEach { (s, e) ->
            baselineReads += 2 // getTextBeforeCursor + getSelectedText, always
            probeReads += 1 // getTextBeforeCursor
            EditorSelectionProbe.selectedText(s, e) { probeReads++; "x" }
        }
        println("LAB-001 IC reads per session: baseline=$baselineReads probe=$probeReads updates=${updates.size}")
        assertEquals(2 * updates.size, baselineReads)
        assertEquals(updates.size + 1, probeReads)
        assertTrue(probeReads * 10 <= baselineReads * 6)
    }
}
