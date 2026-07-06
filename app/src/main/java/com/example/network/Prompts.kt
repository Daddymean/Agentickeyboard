package com.example.network

import com.example.util.ReplyIntents

/**
 * Centralized prompt templates for GeminiManager.
 * Extracted for maintainability, testability, and to reduce duplication in the large manager file.
 * Use string templates or builders here for easy updates.
 */
object Prompts {

    const val VOICE_LOCK_DIRECTIVE =
        "IMPORTANT: Preserve the user's own phrasing and word choices as much as possible. " +
            "Make only the minimal edits needed. Do not add flourishes, filler, emojis, or an " +
            "overproduced marketing tone — the result must still sound like the user wrote it."

    fun fixGrammar(personalizationContext: String, text: String): String = """
        Analyze the following text for spelling, punctuation, styling, or grammar errors. Correct them perfectly.
        Provide a clear, brief explanation of the key correction made.
        
        ${if (personalizationContext.isNotEmpty()) "Context of the user's preferred style:\n$personalizationContext\n" else ""}
        
        Input text: "$text"
        
        Return raw JSON with this exact structure:
        {
          "original": "original text here",
          "corrected": "fully corrected text here",
          "explanation": "explanation of what was fixed here",
          "correctionsCount": 2
        }
    """.trimIndent()

    fun suggestReplies(contextMessage: String, personalizationContext: String, intent: String): String = """
        You are an expert keyboard assistant. The user received this message:
        "$contextMessage"

        ${if (personalizationContext.isNotEmpty()) "Personalization Context (match user's writing habits):\n$personalizationContext\n" else ""}
        ${if (intent.isNotEmpty()) "Reply direction chosen by the user: $intent. ${ReplyIntents.promptDirective(intent)}\n" else ""}
        Generate exactly 3 smart, natural, conversational, and highly context-appropriate replies, at three lengths:
        1. Very short (4 words or fewer)
        2. Medium (roughly 8-12 words)
        3. Detailed (1-2 full sentences)
        ${if (personalizationContext.isNotEmpty()) "Ensure the replies naturally blend with the user's habitual vocabulary, tone, or style of expression if indicated in the personalization context." else ""}

        Return raw JSON with this exact structure:
        {
          "suggestions": ["short reply", "medium reply", "detailed reply"]
        }
    """.trimIndent()

    fun summarizeMessage(personalizationContext: String, text: String): String = """
        Summarize the following text extremely briefly in 1-2 short sentences, suitable for quick reading on a phone screen.
        ${if (personalizationContext.isNotEmpty()) "Adapt the summary explanation to align with the user's style preferences:\n$personalizationContext\n" else ""}
        
        Text to summarize:
        $text
    """.trimIndent()

    // Add similar builders for translateText, rewriteWithTone, composeMessage, explainText, continueText, analyzeTone as needed
    // This keeps GeminiManager focused on orchestration/caching/error handling.

    fun rewriteWithTone(targetTone: String, personalizationContext: String, preserveVoice: Boolean, text: String): String = """
        Rewrite the following text so it reads in a "$targetTone" tone. Preserve the original meaning and approximate length.
        Return ONLY the rewritten text with absolutely no introductory or extra text.
        ${if (personalizationContext.isNotEmpty()) "Blend in the user's habitual vocabulary where natural:\n$personalizationContext\n" else ""}
        ${if (preserveVoice) "$VOICE_LOCK_DIRECTIVE\n" else ""}
        Text:
        $text
    """.trimIndent()

    // ... (other prompts similarly extracted in full refactor)
}
