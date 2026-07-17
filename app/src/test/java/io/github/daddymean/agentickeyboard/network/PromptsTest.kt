package io.github.daddymean.agentickeyboard.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    @Test
    fun translateText_withPersonalization_includesPersonalizationPrompt() {
        val prompt = Prompts.translateText(
            sourceLang = "English",
            targetLang = "French",
            personalizationContext = "Professional and polite",
            text = "Hello, how are you?"
        )

        assertTrue(prompt.contains("from English to French"))
        assertTrue(prompt.contains("Professional and polite"))
        assertTrue(prompt.contains("Maintain the style level (formality, tone) matching the personalization preference"))
        // Check for substring rather than full string with exact whitespace
        assertTrue(prompt.contains("Text:"))
        assertTrue(prompt.contains("Hello, how are you?"))
    }

    @Test
    fun translateText_withoutPersonalization_excludesPersonalizationPrompt() {
        val prompt = Prompts.translateText(
            sourceLang = "English",
            targetLang = "Spanish",
            personalizationContext = "",
            text = "Where is the library?"
        )

        assertTrue(prompt.contains("from English to Spanish"))
        assertFalse(prompt.contains("Maintain the style level (formality, tone) matching the personalization preference"))
        assertTrue(prompt.contains("Text:"))
        assertTrue(prompt.contains("Where is the library?"))
    }
}
