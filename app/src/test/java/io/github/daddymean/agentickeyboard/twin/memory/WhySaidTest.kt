package io.github.daddymean.agentickeyboard.twin.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhySaidTest {
    @Test fun explainsCitedReplyWithSources() {
        val cites = listOf(
            Citation("m:abc", "Journal, 2026-01-02: birthday", 1L, 100L),
            Citation("core:who_i_am", "Core memory: Who I am", null, 50L),
        )
        val reply = CitedReply(
            segments = listOf(
                ReplySegment(CitationGuard.INTRO),
                ReplySegment("\"I love birthdays\"", listOf(0)),
                ReplySegment("Dad is a builder.", listOf(1)),
            ),
            citations = cites,
            grounded = true,
        )
        val e = WhySaid.explain(reply)
        assertTrue(e.grounded)
        assertEquals(3, e.lines.size)
        assertTrue(e.lines[1].sources.single().ref == "m:abc")
        val text = e.render()
        assertTrue(text.contains("Why the twin said this"))
        assertTrue(text.contains("Journal, 2026-01-02"))
        assertTrue(text.contains("m:abc"))
    }

    @Test fun ungroundedShowsNoSource() {
        val e = WhySaid.explain(CitedReply(listOf(ReplySegment(CitationGuard.NO_SOURCE)), emptyList(), false))
        assertFalse(e.grounded)
        assertTrue(e.render().contains(CitationGuard.NO_SOURCE))
    }

    @Test fun notesDroppedSegments() {
        val reply = CitedReply(
            listOf(ReplySegment("\"kept\"", listOf(0))),
            listOf(Citation("m:1", "Journal, 2026-01-01", null, 1L)),
            grounded = true,
            dropped = 2,
        )
        assertTrue(WhySaid.explain(reply).render().contains("2 statement(s) were dropped"))
    }
}
