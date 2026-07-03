package com.example.network

/** Centralized Gemini network configuration. */
object GeminiConfig {
    const val BASE_URL = "https://generativelanguage.googleapis.com/"
    const val DEFAULT_MODEL = "gemini-3.5-flash"
    const val GENERATE_CONTENT_PATH = "v1beta/models/$DEFAULT_MODEL:generateContent"

    const val CONNECT_TIMEOUT_SECONDS = 30L
    const val READ_TIMEOUT_SECONDS = 60L
    const val WRITE_TIMEOUT_SECONDS = 60L

    const val MAX_CLOUD_TEXT_CHARS = 8_000
    const val MAX_PERSONALIZATION_CHARS = 1_500
    const val CLOUD_REDACTION_ENABLED = true
}
