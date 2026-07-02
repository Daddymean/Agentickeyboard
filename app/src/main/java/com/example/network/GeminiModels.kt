package com.example.network

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
    val suggestions: List<String> // recommendations to adjust tone
)

@JsonClass(generateAdapter = true)
data class SuggestionsResponse(
    val suggestions: List<String>
)
