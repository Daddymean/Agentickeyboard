package io.github.daddymean.agentickeyboard.network

import android.util.Log
import io.github.daddymean.agentickeyboard.BuildConfig
import io.github.daddymean.agentickeyboard.util.OnDeviceAi
import io.github.daddymean.agentickeyboard.util.OnDeviceAiRouter
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import io.github.daddymean.agentickeyboard.util.ReplyIntents
import io.github.daddymean.agentickeyboard.util.WritingQualityMeter
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GeminiManager {
    private const val TAG = "GeminiManager"

    // We fetch the API key safely from BuildConfig
    private val apiKey: String = BuildConfig.GEMINI_API_KEY

    private val moshi: Moshi = RetrofitClient.moshi

    /**
     * On-device (Gemini Nano) provider for the offline path, injected by the
     * Application at startup. Null (e.g. in unit tests) simply means every
     * offline request uses the heuristic fallbacks.
     */
    @Volatile
    var onDeviceAi: OnDeviceAi? = null

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
            return@withContext offlineGrammarFix(text)
        }

        val prompt = Prompts.fixGrammar(personalizationContext, text)

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
                offlineGrammarFix(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in fixGrammar", e)
            offlineGrammarFix(text)
        }
    }

    /**
     * Suggests smart response replies based on input message context.
     */
    suspend fun suggestReplies(contextMessage: String, personalizationContext: String = "", intent: String = "", bypassCache: Boolean = false): SuggestionsResponse = withContext(Dispatchers.IO) {
        if (!isApiKeyAvailable()) {
            return@withContext offlineReplies(contextMessage, personalizationContext, intent)
        }

        val prompt = Prompts.suggestReplies(contextMessage, personalizationContext, intent)

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
                offlineReplies(contextMessage, personalizationContext, intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in suggestReplies", e)
            offlineReplies(contextMessage, personalizationContext, intent)
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
            return@withContext offlineSummary(text)
        }

        val prompt = Prompts.summarizeMessage(personalizationContext, text)

        val cacheKey = "summary|$text"
        if (!bypassCache) (cacheGet(cacheKey) as? String)?.let { return@withContext it }

        try {
            val result = generateText(prompt)
            if (result != null) cachePut(cacheKey, result)
            result ?: offlineSummary(text)
        } catch (e: Exception) {
            Log.e(TAG, "Error in summarizeMessage", e)
            offlineSummary(text)
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

        val prompt = Prompts.translateText(sourceLang, targetLang, personalizationContext, text)

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
            return@withContext offlineRewrite(text, targetTone)
        }

        val prompt = Prompts.rewriteWithTone(targetTone, personalizationContext, preserveVoice, text)

        val cacheKey = "rewrite|$preserveVoice|$targetTone|$personalizationContext|$text"
        if (!bypassCache) (cacheGet(cacheKey) as? String)?.let { return@withContext it }

        try {
            val result = generateText(prompt)
            if (result != null) cachePut(cacheKey, result)
            result ?: offlineRewrite(text, targetTone)
        } catch (e: Exception) {
            Log.e(TAG, "Error in rewriteWithTone", e)
            offlineRewrite(text, targetTone)
        }
    }

    /**
     * Drafts a complete message from a short instruction the user typed, e.g.
     * "tell her I'll be 20 minutes late, apologetic" -> an actual message.
     */
    suspend fun composeMessage(instruction: String, targetTone: String, personalizationContext: String = "", preserveVoice: Boolean = false, bypassCache: Boolean = false): String = withContext(Dispatchers.IO) {
        if (instruction.isBlank()) return@withContext ""

        if (!isApiKeyAvailable()) {
            return@withContext offlineCompose(instruction, targetTone, personalizationContext, preserveVoice)
        }

        val prompt = Prompts.composeMessage(instruction, targetTone, personalizationContext, preserveVoice)

        val cacheKey = "compose|$preserveVoice|$targetTone|$personalizationContext|$instruction"
        if (!bypassCache) (cacheGet(cacheKey) as? String)?.let { return@withContext it }

        try {
            val result = generateText(prompt)
            if (result != null) cachePut(cacheKey, result)
            result ?: offlineCompose(instruction, targetTone, personalizationContext, preserveVoice)
        } catch (e: Exception) {
            Log.e(TAG, "Error in composeMessage", e)
            offlineCompose(instruction, targetTone, personalizationContext, preserveVoice)
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

        val prompt = Prompts.explainText(text)

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
            return@withContext offlineContinue(text, personalizationContext, preserveVoice)
        }

        val prompt = Prompts.continueText(text, personalizationContext, preserveVoice)

        val cacheKey = "continue|$preserveVoice|$personalizationContext|$text"
        if (!bypassCache) (cacheGet(cacheKey) as? String)?.let { return@withContext it }

        try {
            val result = generateText(prompt)
            if (result != null) cachePut(cacheKey, result)
            result ?: offlineContinue(text, personalizationContext, preserveVoice)
        } catch (e: Exception) {
            Log.e(TAG, "Error in continueText", e)
            offlineContinue(text, personalizationContext, preserveVoice)
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
            return@withContext offlineTone(text, personalizationContext)
        }

        val prompt = Prompts.analyzeTone(personalizationContext, text)

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
                offlineTone(text, personalizationContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in analyzeTone", e)
            offlineTone(text, personalizationContext)
        }
    }

    // --- Offline routing: on-device Gemini Nano when available, else heuristics ---

    /**
     * Offline grammar fix: on-device proofreading when the model is available,
     * the regex heuristics otherwise. Also the fallback for cloud failures.
     */
    suspend fun offlineGrammarFix(text: String): GrammarCorrectionResponse = OnDeviceAiRouter.route(
        onDeviceAi,
        onDevice = { ai ->
            ai.proofread(text)?.let { corrected ->
                val changed = corrected.trim() != text.trim()
                GrammarCorrectionResponse(
                    original = text,
                    corrected = corrected,
                    explanation = if (changed) "Corrected on-device (Gemini Nano)." else "No errors found on-device.",
                    correctionsCount = if (changed) countWordChanges(text, corrected) else 0
                )
            }
        },
        fallback = { getOfflineGrammarFix(text) }
    )

    /** Offline summary: on-device summarization when available, else heuristic. */
    suspend fun offlineSummary(text: String): String = OnDeviceAiRouter.route(
        onDeviceAi,
        onDevice = { ai -> ai.summarize(text) },
        fallback = { getOfflineSummary(text) }
    )

    /**
     * Offline rewrite: on-device rewriting when available *and* the requested
     * tone/instruction maps onto a preset tone (SHORTEN/ELABORATE/FRIENDLY/
     * PROFESSIONAL); anything else keeps the heuristic fallback.
     */
    suspend fun offlineRewrite(text: String, targetTone: String): String = OnDeviceAiRouter.route(
        onDeviceAi,
        onDevice = { ai -> OnDeviceAi.toneFor(targetTone)?.let { tone -> ai.rewrite(text, tone) } },
        fallback = { getOfflineRewrite(text, targetTone) }
    )

    /** Rough count of differing words between original and corrected text. */
    private fun countWordChanges(original: String, corrected: String): Int {
        val a = original.trim().split("\\s+".toRegex())
        val b = corrected.trim().split("\\s+".toRegex())
        val diffs = a.zip(b).count { (x, y) -> x != y } + kotlin.math.abs(a.size - b.size)
        return diffs.coerceAtLeast(1)
    }

    // --- Phase 2: freeform-prompt routing (replies / compose / continue / tone) ---
    // Gated on the prompt feature's own availability (promptStatus), independent
    // of the Phase 1 task features, so one missing model never disables the other.

    private val promptStatusOf: (OnDeviceAi) -> OnDeviceAiStatus = { it.promptStatus.value }

    /** Offline replies: on-device prompt when available, else the canned heuristic. */
    suspend fun offlineReplies(contextMessage: String, personalizationContext: String = "", intent: String = ""): SuggestionsResponse =
        OnDeviceAiRouter.route(
            onDeviceAi,
            onDevice = { ai ->
                parseReplyLines(ai.generate(Prompts.onDeviceReplies(contextMessage, personalizationContext, intent)))
                    ?.let { SuggestionsResponse(it) }
            },
            fallback = { getOfflineSuggestions(contextMessage, personalizationContext, intent) },
            statusOf = promptStatusOf
        )

    /** Offline compose: on-device prompt when available, else the cloud-mode hint. */
    suspend fun offlineCompose(instruction: String, targetTone: String, personalizationContext: String = "", preserveVoice: Boolean = false): String =
        OnDeviceAiRouter.route(
            onDeviceAi,
            onDevice = { ai -> ai.generate(Prompts.composeMessage(instruction, targetTone, personalizationContext, preserveVoice)) },
            fallback = { "[Offline: compose needs cloud mode] $instruction" },
            statusOf = promptStatusOf
        )

    /** Offline continue: on-device prompt when available, else empty (no suggestion). */
    suspend fun offlineContinue(text: String, personalizationContext: String = "", preserveVoice: Boolean = false): String =
        OnDeviceAiRouter.route(
            onDeviceAi,
            onDevice = { ai -> ai.generate(Prompts.continueText(text, personalizationContext, preserveVoice))?.let { stripDraftEcho(text, it) } },
            fallback = { "" },
            statusOf = promptStatusOf
        )

    /** Offline tone: on-device single-word classification when available, else heuristic. */
    suspend fun offlineTone(text: String, personalizationContext: String = ""): ToneAnalysisResponse =
        OnDeviceAiRouter.route(
            onDeviceAi,
            onDevice = { ai -> normalizeSentiment(ai.generate(Prompts.onDeviceTone(text)))?.let { toneAnalysisForSentiment(it, text) } },
            fallback = { getOfflineToneAnalysis(text) },
            statusOf = promptStatusOf
        )

    /** Split a model's multi-line reply output into up to 3 clean suggestions. */
    internal fun parseReplyLines(raw: String?): List<String>? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.lines()
            .map { it.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim() }
            .map { it.replace(REPLY_NUMBER_PREFIX, "") }
            .map { it.trim().trim('"') }
            .filter { it.isNotBlank() }
            .distinct()
            .take(3)
        return cleaned.takeIf { it.isNotEmpty() }
    }

    /** Map a model tone word onto one of the known sentiments; null if unrecognized. */
    internal fun normalizeSentiment(raw: String?): String? {
        val word = raw?.trim()?.lowercase()?.takeWhile { it.isLetter() }?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            word.startsWith("prof") -> "Professional"
            word.startsWith("joy") || word == "happy" -> "Joyful"
            word.startsWith("emp") -> "Empathetic"
            word.startsWith("apolog") || word == "sorry" -> "Apologetic"
            word.startsWith("urg") -> "Urgent"
            word.startsWith("neu") || word == "casual" -> "Neutral / Casual"
            else -> null
        }
    }

    /** Drop a leading verbatim echo of the draft the continuation model may repeat. */
    private fun stripDraftEcho(draft: String, continuation: String): String {
        val c = continuation.trim()
        val d = draft.trim()
        if (d.isNotEmpty() && c.regionMatches(0, d, 0, d.length, ignoreCase = true)) {
            return c.substring(d.length).trimStart().ifEmpty { c }
        }
        return c
    }

    /** Build a tone response for a known [sentiment], filling dimensions locally. */
    private fun toneAnalysisForSentiment(sentiment: String, text: String): ToneAnalysisResponse {
        val meter = WritingQualityMeter.assess(text)
        val base = when (sentiment) {
            "Apologetic" -> ToneAnalysisResponse(sentiment, 0.85f, listOf("Add context if appropriate.", "Maintain accountability clearly."))
            "Urgent" -> ToneAnalysisResponse(sentiment, 0.80f, listOf("Clarify the exact deadline.", "Include a friendly greeting first to soften."))
            "Joyful" -> ToneAnalysisResponse(sentiment, 0.90f, listOf("Perfect for casual context.", "Add an exclamation mark to boost vibe."))
            "Professional" -> ToneAnalysisResponse(sentiment, 0.95f, listOf("Excellent clarity and structure.", "Ensure action items are defined."))
            "Empathetic" -> ToneAnalysisResponse(sentiment, 0.88f, listOf("Keep acknowledging how they feel.", "Offer one concrete next step."))
            else -> ToneAnalysisResponse(sentiment, 0.70f, listOf("Add descriptive words for clarity.", "Consider using a direct question."))
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

    private val REPLY_NUMBER_PREFIX = "^\\d+[.):]\\s*".toRegex()

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
        val sentiment = when {
            listOf("sorry", "apologize", "pardon", "fault").any { lowercaseText.contains(it) } -> "Apologetic"
            listOf("asap", "need", "now", "hurry", "urgent").any { lowercaseText.contains(it) } -> "Urgent"
            listOf("great", "love", "thanks", "awesome", "happy").any { lowercaseText.contains(it) } -> "Joyful"
            listOf("please", "regards", "sincerely", "confirm", "as discussed").any { lowercaseText.contains(it) } -> "Professional"
            else -> "Neutral / Casual"
        }
        return toneAnalysisForSentiment(sentiment, text)
    }
}
