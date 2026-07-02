package com.example.util

import android.util.Base64
import com.example.db.LearnedCorrection
import com.example.db.UserVocabulary
import com.example.db.WritingLog
import java.nio.charset.StandardCharsets

object PersonalModelSerializer {

    data class AnonymizationStats(
        val emailsRedacted: Int = 0,
        val phonesRedacted: Int = 0,
        val cardsRedacted: Int = 0,
        val urlsRedacted: Int = 0,
        val ipAddressesRedacted: Int = 0,
        val numericIdsRedacted: Int = 0,
        val totalRedactions: Int = 0
    ) {
        operator fun plus(other: AnonymizationStats): AnonymizationStats {
            return AnonymizationStats(
                emailsRedacted = this.emailsRedacted + other.emailsRedacted,
                phonesRedacted = this.phonesRedacted + other.phonesRedacted,
                cardsRedacted = this.cardsRedacted + other.cardsRedacted,
                urlsRedacted = this.urlsRedacted + other.urlsRedacted,
                ipAddressesRedacted = this.ipAddressesRedacted + other.ipAddressesRedacted,
                numericIdsRedacted = this.numericIdsRedacted + other.numericIdsRedacted,
                totalRedactions = this.totalRedactions + other.totalRedactions
            )
        }
    }

    data class ExportResult(
        val serializedContent: String,
        val stats: AnonymizationStats,
        val totalRecords: Int
    )

    /**
     * Escapes a string for embedding inside a JSON string literal. Quotes alone are
     * not enough: backslashes, newlines, and other control characters in typed text
     * would otherwise produce invalid JSON.
     */
    internal fun String.jsonEscape(): String = buildString {
        for (c in this@jsonEscape) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
    }

    /**
     * Sanitizes input text by redacting emails, phone numbers, financial markers, IP addresses, URLs,
     * and long generic numeric IDs to prevent sensitive credential leakage.
     */
    fun sanitizeText(text: String): Pair<String, AnonymizationStats> {
        if (text.isBlank()) return Pair(text, AnonymizationStats())
        
        var sanitized = text
        var emails = 0
        var phones = 0
        var cards = 0
        var urls = 0
        var ips = 0
        var numerics = 0

        // 1. Email Redaction
        val emailRegex = """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""".toRegex()
        val emailMatches = emailRegex.findAll(sanitized).toList()
        if (emailMatches.isNotEmpty()) {
            emails += emailMatches.size
            sanitized = sanitized.replace(emailRegex, "[REDACTED_EMAIL]")
        }

        // 2. Phone Number Redaction (e.g., +1-555-0199, (555) 555-0199, 555-555-0199)
        val phoneRegex = """(?:\+?\d{1,3}[-.\s]?)?(?:\(?\d{3}\)?[-.\s]?\d{3}|\b\d{3})[-.\s]?\d{4}\b""".toRegex()
        val phoneMatches = phoneRegex.findAll(sanitized).toList()
        if (phoneMatches.isNotEmpty()) {
            phones += phoneMatches.size
            sanitized = sanitized.replace(phoneRegex, "[REDACTED_PHONE]")
        }

        // 3. Credit Card or SSN Redaction
        val ccRegex = """\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b""".toRegex()
        val ccMatches = ccRegex.findAll(sanitized).toList()
        if (ccMatches.isNotEmpty()) {
            cards += ccMatches.size
            sanitized = sanitized.replace(ccRegex, "[REDACTED_FINANCIAL]")
        }

        val ssnRegex = """\b\d{3}-\d{2}-\d{4}\b""".toRegex()
        val ssnMatches = ssnRegex.findAll(sanitized).toList()
        if (ssnMatches.isNotEmpty()) {
            cards += ssnMatches.size
            sanitized = sanitized.replace(ssnRegex, "[REDACTED_FINANCIAL]")
        }

        // 4. IP Address Redaction
        val ipRegex = """\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b""".toRegex()
        val ipMatches = ipRegex.findAll(sanitized).toList()
        if (ipMatches.isNotEmpty()) {
            ips += ipMatches.size
            sanitized = sanitized.replace(ipRegex, "[REDACTED_IP]")
        }

        // 5. URL Redaction
        val urlRegex = """\b(?:https?|ftp)://[^\s/$.?#].[^\s]*\b""".toRegex()
        val urlMatches = urlRegex.findAll(sanitized).toList()
        if (urlMatches.isNotEmpty()) {
            urls += urlMatches.size
            sanitized = sanitized.replace(urlRegex, "[REDACTED_URL]")
        }

        // 6. Generic Numeric IDs (e.g. solid sequences of 6+ digits, like private tokens/accounts)
        val numIdRegex = """\b\d{6,}\b""".toRegex()
        val numIdMatches = numIdRegex.findAll(sanitized).toList()
        if (numIdMatches.isNotEmpty()) {
            numerics += numIdMatches.size
            sanitized = sanitized.replace(numIdRegex, "[REDACTED_NUMERIC_ID]")
        }

        val total = emails + phones + cards + urls + ips + numerics
        val stats = AnonymizationStats(
            emailsRedacted = emails,
            phonesRedacted = phones,
            cardsRedacted = cards,
            urlsRedacted = urls,
            ipAddressesRedacted = ips,
            numericIdsRedacted = numerics,
            totalRedactions = total
        )
        return Pair(sanitized, stats)
    }

    /**
     * Serializes telemetry, learned vocabulary, spelling correction histories, and writing logs 
     * into a unified structured export payload.
     */
    fun serialize(
        vocabulary: List<UserVocabulary>,
        corrections: List<LearnedCorrection>,
        logs: List<WritingLog>,
        personaPreference: String,
        stripSensitive: Boolean,
        exportFormat: String
    ): ExportResult {
        var runningStats = AnonymizationStats()
        
        // 1. Process and sanitize vocabulary words (they are individual words, but let's run them through sanitization just in case)
        val vocabList = vocabulary.map { item ->
            val (sanitizedWord, wordStats) = if (stripSensitive) sanitizeText(item.word) else Pair(item.word, AnonymizationStats())
            runningStats += wordStats
            """      { "word": "${sanitizedWord.jsonEscape()}", "count": ${item.count}, "lastUsed": ${item.lastUsed} }"""
        }

        // 2. Process and sanitize spelling corrections
        val corrList = corrections.map { item ->
            val (sanitizedTypo, typoStats) = if (stripSensitive) sanitizeText(item.typo) else Pair(item.typo, AnonymizationStats())
            val (sanitizedCorrection, corrStats) = if (stripSensitive) sanitizeText(item.correction) else Pair(item.correction, AnonymizationStats())
            runningStats += typoStats
            runningStats += corrStats
            """      { "typo": "${sanitizedTypo.jsonEscape()}", "correction": "${sanitizedCorrection.jsonEscape()}", "count": ${item.count} }"""
        }

        // 3. Process and sanitize writing logs
        val logList = logs.map { item ->
            val (sanitizedText, logStats) = if (stripSensitive) sanitizeText(item.originalText) else Pair(item.originalText, AnonymizationStats())
            runningStats += logStats
            """      { "text": "${sanitizedText.jsonEscape()}", "sentiment": "${item.sentiment.jsonEscape()}", "toneScore": ${item.toneScore}, "timestamp": ${item.timestamp} }"""
        }

        val totalRecords = vocabulary.size + corrections.size + logs.size

        // 4. Construct JSON Payload
        val jsonPayload = buildString {
            append("{\n")
            append("  \"exportMetadata\": {\n")
            append("    \"exportVersion\": \"1.0.0\",\n")
            append("    \"exportedAt\": ${System.currentTimeMillis()},\n")
            append("    \"userPersonaPreference\": \"${personaPreference.jsonEscape()}\",\n")
            append("    \"anonymizationApplied\": $stripSensitive,\n")
            append("    \"totalRecordsExported\": $totalRecords,\n")
            append("    \"redactionsSummary\": {\n")
            append("      \"emailsRedacted\": ${runningStats.emailsRedacted},\n")
            append("      \"phonesRedacted\": ${runningStats.phonesRedacted},\n")
            append("      \"cardsRedacted\": ${runningStats.cardsRedacted},\n")
            append("      \"urlsRedacted\": ${runningStats.urlsRedacted},\n")
            append("      \"ipAddressesRedacted\": ${runningStats.ipAddressesRedacted},\n")
            append("      \"numericIdsRedacted\": ${runningStats.numericIdsRedacted},\n")
            append("      \"totalRedactions\": ${runningStats.totalRedactions}\n")
            append("    }\n")
            append("  },\n")
            append("  \"typingPatterns\": {\n")
            append("    \"vocabulary\": [\n")
            append(vocabList.joinToString(",\n"))
            append("\n    ]\n")
            append("  },\n")
            append("  \"correctionHistory\": [\n")
            append(corrList.joinToString(",\n"))
            append("\n  ],\n")
            append("  \"writingLogs\": [\n")
            append(logList.joinToString(",\n"))
            append("\n  ]\n")
            append("}")
        }

        // 5. Apply encoding format
        val finalContent = if (exportFormat == "Base64 Cipher Block") {
            try {
                // Encode to Base64 to represent a secure portable training block
                val bytes = jsonPayload.toByteArray(StandardCharsets.UTF_8)
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (e: Exception) {
                jsonPayload
            }
        } else {
            jsonPayload
        }

        return ExportResult(
            serializedContent = finalContent,
            stats = runningStats,
            totalRecords = totalRecords
        )
    }
}
