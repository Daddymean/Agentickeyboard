package io.github.daddymean.agentickeyboard.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptsTest {

    @Test
    fun `rewriteWithTone constructs basic prompt without optional parameters`() {
        val result = Prompts.rewriteWithTone(
            targetTone = "Professional",
            personalizationContext = "",
            preserveVoice = false,
            text = "hey im running late"
        )

        assertTrue(result.contains("Rewrite the following text so it reads in a \"Professional\" tone."))
        assertTrue(result.contains("Text:\n        hey im running late") || result.contains("Text:\nhey im running late") || result.contains("Text:\n        hey im running late".trimIndent()))
        assertFalse(result.contains("Blend in the user's habitual vocabulary"))
        assertFalse(result.contains(Prompts.VOICE_LOCK_DIRECTIVE))
    }

    @Test
    fun `rewriteWithTone includes personalization context when provided`() {
        val result = Prompts.rewriteWithTone(
            targetTone = "Joyful",
            personalizationContext = "Use lots of exclamation marks.",
            preserveVoice = false,
            text = "the meeting is done"
        )

        assertTrue(result.contains("Blend in the user's habitual vocabulary where natural:\nUse lots of exclamation marks."))
        assertFalse(result.contains(Prompts.VOICE_LOCK_DIRECTIVE))
        assertTrue(result.contains("Text:\n        the meeting is done") || result.contains("Text:\nthe meeting is done") || result.contains("Text:\n        the meeting is done".trimIndent()))
    }

    @Test
    fun `rewriteWithTone includes voice lock directive when preserveVoice is true`() {
        val result = Prompts.rewriteWithTone(
            targetTone = "Empathetic",
            personalizationContext = "",
            preserveVoice = true,
            text = "sorry to hear that"
        )

        assertTrue(result.contains(Prompts.VOICE_LOCK_DIRECTIVE))
        assertFalse(result.contains("Blend in the user's habitual vocabulary"))
        assertTrue(result.contains("Text:\n        sorry to hear that") || result.contains("Text:\nsorry to hear that") || result.contains("Text:\n        sorry to hear that".trimIndent()))
    }

    @Test
    fun `rewriteWithTone includes both context and voice lock when both are enabled`() {
        val result = Prompts.rewriteWithTone(
            targetTone = "Urgent",
            personalizationContext = "Uses short sentences.",
            preserveVoice = true,
            text = "we need to fix the server now"
        )

        assertTrue(result.contains("Blend in the user's habitual vocabulary where natural:\nUses short sentences."))
        assertTrue(result.contains(Prompts.VOICE_LOCK_DIRECTIVE))
        assertTrue(result.contains("Text:\n        we need to fix the server now") || result.contains("Text:\nwe need to fix the server now") || result.contains("Text:\n        we need to fix the server now".trimIndent()))
    }
}
