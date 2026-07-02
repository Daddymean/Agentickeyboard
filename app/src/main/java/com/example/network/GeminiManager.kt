package com.example.network

import android.util.Log
import com.example.BuildConfig
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GeminiManager {
    private const val TAG = "GeminiManager"
    
    // We fetch the API key safely from BuildConfig
    private val apiKey: String = BuildConfig.GEMINI_API_KEY

    private val moshi: Moshi = RetrofitClient.moshi

    /**
     * Checks if the API key is configured and seems valid.
     */
    fun isApiKeyAvailable(): Boolean {
        return apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY"
    }

    /**
     * Corrects grammar and spelling errors. Returns a structured GrammarCorrectionResponse.
     */
    suspend fun fixGrammar(text: String, personalizationContext: String = ""): GrammarCorrectionResponse = withContext(Dispatchers.IO) {
        if (!isApiKeyAvailable()) {
            return@withContext getOfflineGrammarFix(text)
        }

        val prompt = """
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

        try {
            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                generationConfig = GenerationConfig(
                    responseMimeType = "application/json",
                    temperature = 0.2f
                )
            )
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                val adapter = moshi.adapter(GrammarCorrectionResponse::class.java)
                adapter.fromJson(jsonText) ?: getOfflineGrammarFix(text)
            } else {
                getOfflineGrammarFix(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in fixGrammar", e)
            getOfflineGrammarFix(text, "Offline mode fallback (error: ${e.localizedMessage})")
        }
    }

    /**
     * Suggests smart response replies based on input message context.
     */
    suspend fun suggestReplies(contextMessage: String, personalizationContext: String = ""): SuggestionsResponse = withContext(Dispatchers.IO) {
        if (!isApiKeyAvailable()) {
            return@withContext getOfflineSuggestions(contextMessage, personalizationContext)
        }

        val prompt = """
            You are an expert keyboard assistant. The user received this message:
            "$contextMessage"
            
            ${if (personalizationContext.isNotEmpty()) "Personalization Context (match user's writing habits):\n$personalizationContext\n" else ""}
            
            Generate exactly 3 smart, natural, conversational, and highly context-appropriate replies. Keep each suggestion under 5 words.
            ${if (personalizationContext.isNotEmpty()) "Ensure the replies naturally blend with the user's habitual vocabulary, tone, or style of expression if indicated in the personalization context." else ""}
            
            Return raw JSON with this exact structure:
            {
              "suggestions": ["suggestion 1", "suggestion 2", "suggestion 3"]
            }
        """.trimIndent()

        try {
            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                generationConfig = GenerationConfig(
                    responseMimeType = "application/json",
                    temperature = 0.7f
                )
            )
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                val adapter = moshi.adapter(SuggestionsResponse::class.java)
                adapter.fromJson(jsonText) ?: getOfflineSuggestions(contextMessage, personalizationContext)
            } else {
                getOfflineSuggestions(contextMessage, personalizationContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in suggestReplies", e)
            getOfflineSuggestions(contextMessage, personalizationContext)
        }
    }

    /**
     * Summarizes long input message text.
     */
    suspend fun summarizeMessage(text: String, personalizationContext: String = ""): String = withContext(Dispatchers.IO) {
        if (text.trim().split("\\s+".toRegex()).size < 10) {
            return@withContext "Message is too short to summarize."
        }
        
        if (!isApiKeyAvailable()) {
            return@withContext getOfflineSummary(text)
        }

        val prompt = """
            Summarize the following text extremely briefly in 1-2 short sentences, suitable for quick reading on a phone screen.
            ${if (personalizationContext.isNotEmpty()) "Adapt the summary explanation to align with the user's style preferences:\n$personalizationContext\n" else ""}
            
            Text to summarize:
            $text
        """.trimIndent()

        try {
            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                generationConfig = GenerationConfig(temperature = 0.5f)
            )
            val response = RetrofitClient.service.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                ?: getOfflineSummary(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error in summarizeMessage", e)
            getOfflineSummary(text)
        }
    }

    /**
     * Translates text into target language.
     */
    suspend fun translateText(text: String, sourceLang: String, targetLang: String, personalizationContext: String = ""): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        
        if (!isApiKeyAvailable()) {
            return@withContext "[Offline Preview] Translated text from $sourceLang to $targetLang: $text"
        }

        val prompt = """
            Translate the following text from $sourceLang to $targetLang. Return ONLY the translated string with absolutely no introductory or extra text.
            ${if (personalizationContext.isNotEmpty()) "Maintain the style level (formality, tone) matching the personalization preference:\n$personalizationContext\n" else ""}
            
            Text:
            $text
        """.trimIndent()

        try {
            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                generationConfig = GenerationConfig(temperature = 0.3f)
            )
            val response = RetrofitClient.service.generateContent(apiKey, request)
            response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
                ?: "[Translation Failed] $text"
        } catch (e: Exception) {
            Log.e(TAG, "Error in translateText", e)
            "[Translation Error] $text"
        }
    }

    /**
     * Distills the sentiment and analyzes the tone.
     */
    suspend fun analyzeTone(text: String, personalizationContext: String = ""): ToneAnalysisResponse = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext ToneAnalysisResponse("Neutral", 1.0f, listOf("No text provided to analyze."))
        }

        if (!isApiKeyAvailable()) {
            return@withContext getOfflineToneAnalysis(text)
        }

        val prompt = """
            Analyze the sentiment and communication tone of this keyboard text input:
            "$text"
            
            Identify the primary tone category (e.g. Professional, Joyful, Empathetic, Aggressive, Sarcastic, Apologetic, Urgent).
            Estimate a tone score / confidence value between 0.0 and 1.0.
            Provide exactly 2 actionable tips/suggestions to adjust or improve communication precision.
            
            ${if (personalizationContext.isNotEmpty()) "Contrast this text against the user's baseline writing habit to provide tailored recommendations:\n$personalizationContext\n" else ""}
            
            Return raw JSON with this exact structure:
            {
              "sentiment": "ToneCategory",
              "toneScore": 0.85,
              "suggestions": [
                "Tip 1 to refine the tone",
                "Tip 2 to refine the tone"
              ]
            }
        """.trimIndent()

        try {
            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                generationConfig = GenerationConfig(
                    responseMimeType = "application/json",
                    temperature = 0.3f
                )
            )
            val response = RetrofitClient.service.generateContent(apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            if (jsonText != null) {
                val adapter = moshi.adapter(ToneAnalysisResponse::class.java)
                adapter.fromJson(jsonText) ?: getOfflineToneAnalysis(text)
            } else {
                getOfflineToneAnalysis(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in analyzeTone", e)
            getOfflineToneAnalysis(text)
        }
    }

    // --- Offline Fallback Implementations for basic text processing & privacy ---

    private fun getOfflineGrammarFix(text: String, explanation: String = "Offline local grammar helper applied"): GrammarCorrectionResponse {
        var corrected = text
        var fixesCount = 0

        // Local regex replacements for common typos and rules
        val corrections = mapOf(
            "\\bteh\\b" to "the",
            "\\bi\\b" to "I",
            "\\barent\\b" to "aren't",
            "\\bcant\\b" to "can't",
            "\\bdont\\b" to "don't",
            "\\bshouldnt\\b" to "shouldn't",
            "\\bwont\\b" to "won't",
            "\\breciever\\b" to "receiver",
            "\\brecieve\\b" to "receive",
            "\\bseperate\\b" to "separate",
            "\\bdefinately\\b" to "definitely"
        )

        for ((typo, fix) in corrections) {
            val regex = typo.toRegex(RegexOption.IGNORE_CASE)
            if (regex.containsMatchIn(corrected)) {
                corrected = corrected.replace(regex, fix)
                fixesCount++
            }
        }

        // Capitalize sentences
        val sentenceRegex = "(?<=^|[.!?]\\s)([a-z])".toRegex()
        if (sentenceRegex.containsMatchIn(corrected)) {
            corrected = corrected.replace(sentenceRegex) { it.value.uppercase() }
            fixesCount++
        }

        return GrammarCorrectionResponse(
            original = text,
            corrected = corrected,
            explanation = if (fixesCount > 0) explanation else "No immediate offline errors detected.",
            correctionsCount = fixesCount
        )
    }

    private fun getOfflineSuggestions(contextMessage: String, personalizationContext: String = ""): SuggestionsResponse {
        val lowercaseContext = contextMessage.lowercase()
        val isProfessional = personalizationContext.contains("Professional", ignoreCase = true)
        val isJoyful = personalizationContext.contains("Joyful", ignoreCase = true) || personalizationContext.contains("Friendly", ignoreCase = true)
        
        val suggestions = when {
            lowercaseContext.contains("hello") || lowercaseContext.contains("hi") || lowercaseContext.contains("hey") -> {
                when {
                    isProfessional -> listOf("Dear sender, hello.", "Good day, how can I assist?", "Hello, thank you for reaching out.")
                    isJoyful -> listOf("Hey! Great to hear from you! 🎉", "Hi! Hope you're having an awesome day!", "Hello there! 😊")
                    else -> listOf("Hello!", "Hey, how are you?", "Hi there!")
                }
            }
            lowercaseContext.contains("where") || lowercaseContext.contains("location") -> {
                when {
                    isProfessional -> listOf("I will arrive shortly.", "I am currently at the office.", "I will confirm the location.")
                    isJoyful -> listOf("Omw! So excited! 🚀", "Almost there, save me a spot!", "Heading your way!")
                    else -> listOf("I'm on my way.", "Almost there!", "Let's meet at...")
                }
            }
            lowercaseContext.contains("time") || lowercaseContext.contains("when") -> {
                when {
                    isProfessional -> listOf("Let us schedule for 5 PM.", "Does tomorrow work for you?", "I am available at your convenience.")
                    isJoyful -> listOf("Let's hang out at 5! 🕒", "Tomorrow is perfect!", "Free whenever you are!")
                    else -> listOf("Let's do 5 PM.", "Does tomorrow work?", "I am free now.")
                }
            }
            else -> {
                when {
                    isProfessional -> listOf("Understood, thank you.", "I will check and follow up.", "Regards.")
                    isJoyful -> listOf("Awesome, thank you! ✨", "Sounds perfect! Daily win!", "Count me in!")
                    else -> listOf("Sounds good!", "Thanks for letting me know.", "I'll reply soon.")
                }
            }
        }
        return SuggestionsResponse(suggestions)
    }

    private fun getOfflineSummary(text: String): String {
        val words = text.trim().split("\\s+".toRegex())
        return if (words.size > 8) {
            "[Local Summary] Brief overview: " + words.take(6).joinToString(" ") + "... (" + words.size + " words total)"
        } else {
            text
        }
    }

    private fun getOfflineToneAnalysis(text: String): ToneAnalysisResponse {
        val lowercaseText = text.lowercase()
        
        var isJoyful = lowercaseText.contains("great") || lowercaseText.contains("love") || lowercaseText.contains("thanks") || lowercaseText.contains("awesome") || lowercaseText.contains("happy")
        var isProfessional = lowercaseText.contains("please") || lowercaseText.contains("regards") || lowercaseText.contains("sincerely") || lowercaseText.contains("confirm") || lowercaseText.contains("as discussed")
        var isUrgent = lowercaseText.contains("asap") || lowercaseText.contains("need") || lowercaseText.contains("now") || lowercaseText.contains("hurry") || lowercaseText.contains("urgent")
        var isApologetic = lowercaseText.contains("sorry") || lowercaseText.contains("apologize") || lowercaseText.contains("pardon") || lowercaseText.contains("fault")

        return when {
            isApologetic -> ToneAnalysisResponse(
                sentiment = "Apologetic",
                toneScore = 0.85f,
                suggestions = listOf("Add context if appropriate.", "Maintain accountability clearly.")
            )
            isUrgent -> ToneAnalysisResponse(
                sentiment = "Urgent",
                toneScore = 0.80f,
                suggestions = listOf("Clarify the exact deadline.", "Include a friendly greeting first to soften.")
            )
            isJoyful -> ToneAnalysisResponse(
                sentiment = "Joyful",
                toneScore = 0.90f,
                suggestions = listOf("Perfect for casual context.", "Add an exclamation mark to boost vibe.")
            )
            isProfessional -> ToneAnalysisResponse(
                sentiment = "Professional",
                toneScore = 0.95f,
                suggestions = listOf("Excellent clarity and structure.", "Ensure action items are defined.")
            )
            else -> ToneAnalysisResponse(
                sentiment = "Neutral / Casual",
                toneScore = 0.70f,
                suggestions = listOf("Add descriptive words for clarity.", "Consider using a direct question.")
            )
        }
    }
}
