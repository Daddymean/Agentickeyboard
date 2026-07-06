package io.github.daddymean.agentickeyboard.network

import io.github.daddymean.agentickeyboard.util.ReplyIntents

/**
 * Centralized prompt templates for GeminiManager.
 * Extracted for maintainability, testability, and to reduce duplication in the large manager file.
 * All prompt construction logic lives here.
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

    fun translateText(sourceLang: String, targetLang: String, personalizationContext: String, text: String): String = """
        Translate the following text from $sourceLang to $targetLang. Return ONLY the translated string with absolutely no introductory or extra text.
        ${if (personalizationContext.isNotEmpty()) "Maintain the style level (formality, tone) matching the personalization preference:\n$personalizationContext\n" else ""}
        
        Text:
        $text
    """.trimIndent()

    fun rewriteWithTone(targetTone: String, personalizationContext: String, preserveVoice: Boolean, text: String): String = """
        Rewrite the following text so it reads in a "$targetTone" tone. Preserve the original meaning and approximate length.
        Return ONLY the rewritten text with absolutely no introductory or extra text.
        ${if (personalizationContext.isNotEmpty()) "Blend in the user's habitual vocabulary where natural:\n$personalizationContext\n" else ""}
        ${if (preserveVoice) "$VOICE_LOCK_DIRECTIVE\n" else ""}
        Text:
        $text
    """.trimIndent()

    fun composeMessage(instruction: String, targetTone: String, personalizationContext: String, preserveVoice: Boolean): String = """
        The user wants you to write a message on their behalf. Their instruction describes what the message should say:
        "$instruction"

        Write the actual message they should send, in a "$targetTone" tone, suitable for a mobile chat. Keep it natural and concise.
        Return ONLY the message text with absolutely no introductory or extra text.
        ${if (personalizationContext.isNotEmpty()) "Match the user's habitual voice:\n$personalizationContext\n" else ""}
        ${if (preserveVoice) "$VOICE_LOCK_DIRECTIVE\n" else ""}
    """.trimIndent()

    fun explainText(text: String): String = """
        Explain the following text in plain, simple language a layperson would understand.
        Keep the explanation to 1-3 short sentences suitable for a phone screen. Return ONLY the explanation.

        Text:
        $text
    """.trimIndent()

    fun continueText(text: String, personalizationContext: String, preserveVoice: Boolean): String = """
        The user is drafting a message and wants you to continue it naturally in their voice:
        "$text"

        Write the next 5-20 words that continue the draft. Return ONLY the continuation text - do NOT repeat the original draft, do not add quotes or commentary. If the draft ends mid-word, complete that word first.
        ${if (personalizationContext.isNotEmpty()) "Match the user's habitual voice:\n$personalizationContext\n" else ""}
        ${if (preserveVoice) "$VOICE_LOCK_DIRECTIVE\n" else ""}
    """.trimIndent()

    fun analyzeTone(personalizationContext: String, text: String): String = """
        Analyze the sentiment and communication tone of this keyboard text input:
        "$text"
        
        Identify the primary tone category (e.g. Professional, Joyful, Empathetic, Aggressive, Sarcastic, Apologetic, Urgent).
        Estimate a tone score / confidence value between 0.0 and 1.0.
        Provide exactly 2 actionable tips/suggestions to adjust or improve communication precision.
        Also rate these human-framed writing-quality dimensions as short levels (labels, never grades or numbers):
        - clarity: exactly one of "Clear", "OK", "Dense"
        - warmth: exactly one of "Warm", "Neutral", "Cold"
        - firmness: exactly one of "Firm", "Balanced", "Soft"
        - risk: how likely the message lands badly — exactly one of "Low", "Medium", "High"
        And write "note": one plain-language remark of at most 8 words (e.g. "clear but cold", "friendly but hedged").

        ${if (personalizationContext.isNotEmpty()) "Contrast this text against the user's baseline writing habit to provide tailored recommendations:\n$personalizationContext\n" else ""}

        Return raw JSON with this exact structure:
        {
          "sentiment": "ToneCategory",
          "toneScore": 0.85,
          "suggestions": [
            "Tip 1 to refine the tone",
            "Tip 2 to refine the tone"
          ],
          "clarity": "Clear",
          "warmth": "Neutral",
          "firmness": "Balanced",
          "risk": "Low",
          "note": "clear but cold"
        }
    """.trimIndent()
}
