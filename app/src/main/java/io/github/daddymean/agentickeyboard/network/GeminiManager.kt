package io.github.daddymean.agentickeyboard.network

import android.util.Log
import io.github.daddymean.agentickeyboard.BuildConfig
import io.github.daddymean.agentickeyboard.util.ReplyIntents
import io.github.daddymean.agentickeyboard.util.WritingQualityMeter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GeminiManager {
    private const val TAG = "GeminiManager"

    // Appended to rewrite/compose/continue prompts when the user's voice-lock
    // setting is on: output must stay recognizably in the user's own words.
    private const val VOICE_LOCK_DIRECTIVE =
        "IMPORTANT: Preserve the user's own phrasing and word choices as much as possible. " +
            "Make only the minimal edits needed. Do not add flourishes, filler, emojis, or an " +
            "overproduced marketing tone — the result must still sound like the user wrote it."
    
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
     * Models occasionally wrap structured output in markdown code fences even when
     * a JSON mime type is requested; strip them before parsing.
     */
    private fun extractJson(raw: String): String {
        var text = raw.trim()
        if (text.startsWith("```")) {
            text = text.removePrefix("```json").removePrefix("```").trim()
            text = text.removeSuffix("```").trim()
        }
        return text
    }

    // Small LRU cache so repeated identical requests (double-tapped actions,
    // debounced background proofreads of unchanged text) don't re-bill the API.
    private val responseCache = object : LinkedHashMap<String, Any>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Any>): Boolean = size > 48
    }

    private fun cacheGet(key: String): Any? = synchronized(responseCache) { responseCache[key] }

    private fun cachePut(key: String, value: Any) {
        synchronized(responseCache) { responseCache[key] = value }
    }

    /** Runs a plain-text generation request, returning the trimmed reply or null. */
    private suspend fun generateText(prompt: String): String? {
        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = prompt))))
        )
        val response = RetrofitClient.service.generateContent(BuildConfig.GEMINI_MODEL, apiKey, request)
        return response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text?.trim()
    }

    /**
     * Corrects grammar and spelling errors. Returns a structured GrammarCorrectionResponse.
     */
    suspend fun fixGrammar(text: String, personalizationContext: String = "", bypassCache: Boolean = false): GrammarCorrectionResponse = withContext(Dispatchers.IO) {
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

        val cacheKey = "grammar|$personalizationContext|$text"
        if (!bypassCache) (cacheGet(cacheKey) as? GrammarCorrectionResponse)?.let { return@withContext it }

        try {
            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                // Gemini 3 models are tuned for default sampling settings; only the
                // response mime type is pinned for structured output.
                generationConfig = GenerationConfig(responseMimeType = "application/json")
            )
            val response = RetrofitClient.service.generateContent(BuildConfig.GEMINI_MODEL, apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            val parsed = jsonText?.let { moshi.adapter(GrammarCorrectionResponse::class.java).fromJson(extractJson(it)) }
            if (parsed != null) {
                cachePut(cacheKey, parsed)
                parsed
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
    suspend fun suggestReplies(contextMessage: String, personalizationContext: String = "", intent: String = "", bypassCache: Boolean = false): SuggestionsResponse = withContext(Dispatchers.IO) {
        if (!isApiKeyAvailable()) {
            return@withContext getOfflineSuggestions(contextMessage, personalizationContext, intent)
        }

        val prompt = """
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

        val cacheKey = "replies|$intent|$personalizationContext|$contextMessage"
        if (!bypassCache) (cacheGet(cacheKey) as? SuggestionsResponse)?.let { return@withContext it }

        try {
            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                generationConfig = GenerationConfig(responseMimeType = "application/json")
            )
            val response = RetrofitClient.service.generateContent(BuildConfig.GEMINI_MODEL, apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            val parsed = jsonText?.let { moshi.adapter(SuggestionsResponse::class.java).fromJson(extractJson(it)) }
            if (parsed != null) {
                cachePut(cacheKey, parsed)
                parsed
            } else {
                getOfflineSuggestions(contextMessage, personalizationContext, intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in suggestReplies", e)
            getOfflineSuggestions(contextMessage, personalizationContext, intent)
        }
    }

    /**
     * Summarizes long input message text.
     */
    suspend fun summarizeMessage(text: String, personalizationContext: String = "", bypassCache: Boolean = false): String = withContext(Dispatchers.IO) {
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

        val cacheKey = "summary|$text"
        if (!bypassCache) (cacheGet(cacheKey) as? String)?.let { return@withContext it }

        try {
            val result = generateText(prompt)
            if (result != null) cachePut(cacheKey, result)
            result ?: getOfflineSummary(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error in summarizeMessage", e)
            getOfflineSummary(text)
        }
    }

    /**
     * Translates text into target language.
     */
    suspend fun translateText(text: String, sourceLang: String, targetLang: String, personalizationContext: String = "", bypassCache: Boolean = false): String = withContext(Dispatchers.IO) {
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

        val cacheKey = "translate|$sourceLang|$targetLang|$text"
        if (!bypassCache) (cacheGet(cacheKey) as? String)?.let { return@withContext it }

        try {
            val result = generateText(prompt)
            if (result != null) cachePut(cacheKey, result)
            result ?: "[Translation Failed] $text"
        } catch (e: Exception) {
            Log.e(TAG, "Error in translateText", e)
            "[Translation Error] $text"
        }
    }

    /**
     * Rewrites text to match a target tone/persona while preserving meaning.
     */
    suspend fun rewriteWithTone(text: String, targetTone: String, personalizationContext: String = "", preserveVoice: Boolean = false, bypassCache: Boolean = false): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""

        if (!isApiKeyAvailable()) {
            return@withContext getOfflineRewrite(text, targetTone)
        }

        val prompt = """
            Rewrite the following text so it reads in a "$targetTone" tone. Preserve the original meaning and approximate length.
            Return ONLY the rewritten text with absolutely no introductory or extra text.
            ${if (personalizationContext.isNotEmpty()) "Blend in the user's habitual vocabulary where natural:\n$personalizationContext\n" else ""}
            ${if (preserveVoice) "$VOICE_LOCK_DIRECTIVE\n" else ""}
            Text:
            $text
        """.trimIndent()

        val cacheKey = "rewrite|$preserveVoice|$targetTone|$personalizationContext|$text"
        if (!bypassCache) (cacheGet(cacheKey) as? String)?.let { return@withContext it }

        try {
            val result = generateText(prompt)
            if (result != null) cachePut(cacheKey, result)
            result ?: getOfflineRewrite(text, targetTone)
        } catch (e: Exception) {
            Log.e(TAG, "Error in rewriteWithTone", e)
            getOfflineRewrite(text, targetTone)
        }
    }

    /**
     * Drafts a complete message from a short instruction the user typed, e.g.
     * "tell her I'll be 20 minutes late, apologetic" -> an actual message.
     */
    suspend fun composeMessage(instruction: String, targetTone: String, personalizationContext: String = "", preserveVoice: Boolean = false, bypassCache: Boolean = false): String = withContext(Dispatchers.IO) {
        if (instruction.isBlank()) return@withContext ""

        if (!isApiKeyAvailable()) {
            return@withContext "[Offline: compose needs cloud mode] $instruction"
        }

        val prompt = """
            The user wants you to write a message on their behalf. Their instruction describes what the message should say:
            "$instruction"

            Write the actual message they should send, in a "$targetTone" tone, suitable for a mobile chat. Keep it natural and concise.
            Return ONLY the message text with absolutely no introductory or extra text.
            ${if (personalizationContext.isNotEmpty()) "Match the user's habitual voice:\n$personalizationContext\n" else ""}
            ${if (preserveVoice) "$VOICE_LOCK_DIRECTIVE\n" else ""}
        """.trimIndent()

        val cacheKey = "compose|$preserveVoice|$targetTone|$personalizationContext|$instruction"
        if (!bypassCache) (cacheGet(cacheKey) as? String)?.let { return@withContext it }

        try {
            val result = generateText(prompt)
            if (result != null) cachePut(cacheKey, result)
            result ?: "[Compose failed] $instruction"
        } catch (e: Exception) {
            Log.e(TAG, "Error in composeMessage", e)
            "[Compose error] ${e.localizedMessage}"
        }
    }

    /**
     * Explains dense or jargon-heavy text (e.g. from the clipboard) in plain language.
     */
    suspend fun explainText(text: String, bypassCache: Boolean = false): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""

        if (!isApiKeyAvailable()) {
            return@withContext "[Offline: explanations need cloud mode]"
        }

        val prompt = """
            Explain the following text in plain, simple language a layperson would understand.
            Keep the explanation to 1-3 short sentences suitable for a phone screen. Return ONLY the explanation.

            Text:
            $text
        """.trimIndent()

        val cacheKey = "explain|$text"
        if (!bypassCache) (cacheGet(cacheKey) as? String)?.let { return@withContext it }

        try {
            val result = generateText(prompt)
            if (result != null) cachePut(cacheKey, result)
            result ?: "[Explanation failed]"
        } catch (e: Exception) {
            Log.e(TAG, "Error in explainText", e)
            "[Explanation error] ${e.localizedMessage}"
        }
    }

    /**
     * Continues the user's draft mid-thought in their own voice. Returns only the
     * continuation (not the original text).
     */
    suspend fun continueText(text: String, personalizationContext: String = "", preserveVoice: Boolean = false, bypassCache: Boolean = false): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""

        if (!isApiKeyAvailable()) {
            return@withContext "[Offline: continue needs cloud mode]"
        }

        val prompt = """
            The user is drafting a message and wants you to continue it naturally in their voice:
            "$text"

            Write the next 5-20 words that continue the draft. Return ONLY the continuation text - do NOT repeat the original draft, do not add quotes or commentary. If the draft ends mid-word, complete that word first.
            ${if (personalizationContext.isNotEmpty()) "Match the user's habitual voice:\n$personalizationContext\n" else ""}
            ${if (preserveVoice) "$VOICE_LOCK_DIRECTIVE\n" else ""}
        """.trimIndent()

        val cacheKey = "continue|$preserveVoice|$personalizationContext|$text"
        if (!bypassCache) (cacheGet(cacheKey) as? String)?.let { return@withContext it }

        try {
            val result = generateText(prompt)
            if (result != null) cachePut(cacheKey, result)
            result ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error in continueText", e)
            ""
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

        val cacheKey = "tone|$personalizationContext|$text"
        (cacheGet(cacheKey) as? ToneAnalysisResponse)?.let { return@withContext it }

        try {
            val request = GenerateContentRequest(
                contents = listOf(Content(parts = listOf(Part(text = prompt)))),
                generationConfig = GenerationConfig(responseMimeType = "application/json")
            )
            val response = RetrofitClient.service.generateContent(BuildConfig.GEMINI_MODEL, apiKey, request)
            val jsonText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
            val parsed = jsonText?.let { moshi.adapter(ToneAnalysisResponse::class.java).fromJson(extractJson(it)) }
                // Length is computable locally, so never trust the model for it.
                ?.copy(lengthLabel = WritingQualityMeter.lengthLabel(text))
            if (parsed != null) {
                cachePut(cacheKey, parsed)
                parsed
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

    private fun getOfflineSuggestions(contextMessage: String, personalizationContext: String = "", intent: String = ""): SuggestionsResponse {
        if (intent.isNotEmpty()) {
            ReplyIntents.offlineReplies(intent)?.let { return SuggestionsResponse(it) }
        }
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

    private fun getOfflineRewrite(text: String, targetTone: String): String {
        // Best-effort local fallback: cannot restyle without a model, so return the
        // original text so the user never loses what they typed.
        return "[Offline: $targetTone rewrite unavailable] $text"
    }

    private fun getOfflineToneAnalysis(text: String): ToneAnalysisResponse {
        val lowercaseText = text.lowercase()

        val isJoyful = lowercaseText.contains("great") || lowercaseText.contains("love") || lowercaseText.contains("thanks") || lowercaseText.contains("awesome") || lowercaseText.contains("happy")
        val isProfessional = lowercaseText.contains("please") || lowercaseText.contains("regards") || lowercaseText.contains("sincerely") || lowercaseText.contains("confirm") || lowercaseText.contains("as discussed")
        val isUrgent = lowercaseText.contains("asap") || lowercaseText.contains("need") || lowercaseText.contains("now") || lowercaseText.contains("hurry") || lowercaseText.contains("urgent")
        val isApologetic = lowercaseText.contains("sorry") || lowercaseText.contains("apologize") || lowercaseText.contains("pardon") || lowercaseText.contains("fault")

        val meter = WritingQualityMeter.assess(text)
        val base = when {
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
        return base.copy(
            clarity = meter.clarity,
            warmth = meter.warmth,
            firmness = meter.firmness,
            risk = meter.risk,
            lengthLabel = meter.lengthLabel,
            note = meter.note
        )
    }
}
