package io.github.daddymean.agentickeyboard.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GenerateContentRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null,
    val systemInstruction: Content? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    val responseMimeType: String? = null,
    val temperature: Float? = null
)

@JsonClass(generateAdapter = true)
data class GenerateContentResponse(
    val candidates: List<Candidate>? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content? = null
)

// Specific parsing classes for JSON responses from Gemini
@JsonClass(generateAdapter = true)
data class GrammarCorrectionResponse(
    val original: String,
    val corrected: String,
    val explanation: String,
    val correctionsCount: Int
)

@JsonClass(generateAdapter = true)
data class ToneAnalysisResponse(
    val sentiment: String, // e.g. "Positive", "Professional", "Anxious", etc.
    val toneScore: Float,  // e.g. 0.85
    val suggestions: List<String>, // recommendations to adjust tone
    // Writing-quality meter: human-framed levels, not grades (null = not assessed)
    val clarity: String? = null,   // Clear / OK / Dense
    val warmth: String? = null,    // Warm / Neutral / Cold
    val firmness: String? = null,  // Firm / Balanced / Soft
    val risk: String? = null,      // Low / Medium / High chance of landing badly
    val lengthLabel: String? = null, // computed locally, e.g. "Tight", "Long for chat"
    val note: String? = null       // one plain-language note, e.g. "clear but cold"
)

@JsonClass(generateAdapter = true)
data class SuggestionsResponse(
    val suggestions: List<String>
)
