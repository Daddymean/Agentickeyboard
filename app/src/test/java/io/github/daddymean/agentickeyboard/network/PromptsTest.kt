package io.github.daddymean.agentickeyboard.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    // Interpolated multi-line context defeats trimIndent(), so compare trimmed lines.
    private fun lines(prompt: String) = prompt.lines().map { it.trim() }

    private fun assertEndsWithText(prompt: String, text: String) {
        assertEquals(listOf("Text:", text), lines(prompt).takeLast(2))
    }

    @Test
    fun translateTextAddsStyleGuidanceOnlyWithPersonalization() {
        val styled = Prompts.translateText("English", "French", "Professional and polite", "Hello, how are you?")
        assertTrue(styled.contains("from English to French"))
        assertTrue(styled.contains("matching the personalization preference:\nProfessional and polite"))
        assertEndsWithText(styled, "Hello, how are you?")

        val plain = Prompts.translateText("English", "Spanish", "", "Where is the library?")
        assertTrue(plain.contains("from English to Spanish"))
        assertFalse(plain.contains("personalization preference"))
        assertEndsWithText(plain, "Where is the library?")
    }

    @Test
    fun rewriteWithToneIncludesContextAndVoiceLockIndependently() {
        val habit = "Blend in the user's habitual vocabulary where natural:"
        for (withContext in listOf(false, true)) {
            for (preserveVoice in listOf(false, true)) {
                val prompt = Prompts.rewriteWithTone(
                    targetTone = "Professional",
                    personalizationContext = if (withContext) "Uses short sentences." else "",
                    preserveVoice = preserveVoice,
                    text = "hey im running late"
                )
                val label = "context=$withContext voice=$preserveVoice"
                assertTrue(label, prompt.contains("reads in a \"Professional\" tone."))
                assertEquals(label, withContext, prompt.contains("$habit\nUses short sentences."))
                assertEquals(label, preserveVoice, prompt.contains(Prompts.VOICE_LOCK_DIRECTIVE))
                assertEndsWithText(prompt, "hey im running late")
            }
        }
    }
}
