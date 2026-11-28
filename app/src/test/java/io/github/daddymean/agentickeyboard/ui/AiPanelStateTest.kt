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
        assertTrue(AiPanelState.Error("Network timeout").hasResult)
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
        assertNull(AiPanelState.Error("Rewrite failed").refinableText)
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
        assertNull(AiPanelState.Error("failed").sourceText)
    }

    @Test
    fun errorMessagesNeverContainApiKeyOrPromptText() {
        val apiKey = "AIzaSyTestLeakKey_1234567890"
        val prompt = "Please rewrite my medical history: I take lisinopril every morning"
        val thrown = RuntimeException(
            "HTTP 400 https://generativelanguage.googleapis.com/v1beta/models/gemini-flash:generateContent?key=$apiKey body=$prompt"
        )
        val prefixes = listOf(
            "Grammar check failed",
            "Failed to summarize text",
            "Translation error",
            "Rewrite error",
            "Compose error",
            "Explanation error",
            "Tone analysis failed",
        )
        for (prefix in prefixes) {
            val message = aiPanelError(prefix, thrown).message
            assertEquals("$prefix: RuntimeException", message)
            assertFalse(message.contains(apiKey))
            assertFalse(message.contains(prompt))
            assertFalse(message.contains("lisinopril"))
            assertFalse(message.contains("AIza"))
            assertFalse(message.contains("medical history"))
        }
    }

    @Test
    fun viewModelErrorPanelsUseSanitizedFactory() {
        val roots = listOf(
            java.io.File("src/main/java"),
            java.io.File("app/src/main/java"),
        )
        val root = roots.first { it.isDirectory }
        val viewModel = java.io.File(root, "io/github/daddymean/agentickeyboard/ui/KeyboardViewModel.kt")
        val gemini = java.io.File(root, "io/github/daddymean/agentickeyboard/network/GeminiManager.kt")
        assertTrue(viewModel.isFile)
        assertTrue(gemini.isFile)
        val vmSrc = viewModel.readText()
        val gmSrc = gemini.readText()
        assertFalse(vmSrc.contains("AiPanelState.Error("))
        assertTrue(vmSrc.contains("aiPanelError("))
        assertFalse(vmSrc.contains("localizedMessage"))
        assertFalse(gmSrc.contains("e.localizedMessage"))
        assertTrue(gmSrc.contains("SafeLog.errorName(e)"))
    }
}
