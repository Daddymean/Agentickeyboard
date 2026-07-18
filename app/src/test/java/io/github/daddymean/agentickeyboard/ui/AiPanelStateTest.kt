package io.github.daddymean.agentickeyboard.ui

import io.github.daddymean.agentickeyboard.network.GrammarCorrectionResponse
import io.github.daddymean.agentickeyboard.network.ToneAnalysisResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPanelStateTest {

    @Test
    fun onlyCompletedPanelsAreResults() {
        assertFalse(AiPanelState.Idle.hasResult)
        assertFalse(AiPanelState.Loading.hasResult)
        assertFalse(AiPanelState.ReplyIntent("message").hasResult)
        assertTrue(AiPanelState.Replies(listOf("Sure")).hasResult)
        assertTrue(AiPanelState.Explanation("Plain language").hasResult)
    }

    @Test
    fun refinableTextMatchesSupportedPanelTypes() {
        val grammar = AiPanelState.Grammar(
            GrammarCorrectionResponse("teh draft", "the draft", "Fixed typo", 1)
        )
        assertEquals("the draft", grammar.refinableText)
        assertEquals("summary", AiPanelState.Summary("summary", "long source").refinableText)
        assertEquals("translation", AiPanelState.Translation("translation", "source").refinableText)
        assertEquals("rewrite", AiPanelState.Rewrite("rewrite", "source", "Professional").refinableText)
        assertEquals("compose", AiPanelState.Compose("compose").refinableText)
        assertEquals("continue", AiPanelState.Continuation("continue").refinableText)
        assertNull(AiPanelState.Explanation("explain").refinableText)
        assertNull(AiPanelState.Tone(ToneAnalysisResponse("Neutral", 0.8f, emptyList())).refinableText)
    }

    @Test
    fun comparisonSourceTravelsWithItsResult() {
        val grammar = AiPanelState.Grammar(
            GrammarCorrectionResponse("before", "after", "Changed", 1)
        )
        assertEquals("before", grammar.sourceText)
        assertEquals("summary source", AiPanelState.Summary("short", "summary source").sourceText)
        assertEquals("translation source", AiPanelState.Translation("hola", "translation source").sourceText)
        assertEquals("rewrite source", AiPanelState.Rewrite("new", "rewrite source", "Warm").sourceText)
        assertNull(AiPanelState.Compose("draft").sourceText)
    }
}
