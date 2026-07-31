package io.github.daddymean.agentickeyboard.util

import android.util.Base64
import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import io.github.daddymean.agentickeyboard.db.WritingLog
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.nio.charset.StandardCharsets

// --- Import schema (mirrors the export payload written by serialize()) ---

@JsonClass(generateAdapter = true)
data class ImportedVocabulary(val word: String, val count: Int = 1, val lastUsed: Long = 0L)

@JsonClass(generateAdapter = true)
data class ImportedCorrection(val typo: String, val correction: String, val count: Int = 1)

@JsonClass(generateAdapter = true)
data class ImportedLog(val text: String, val sentiment: String = "", val toneScore: Float = 0f, val timestamp: Long = 0L)

@JsonClass(generateAdapter = true)
data class ImportedTypingPatterns(val vocabulary: List<ImportedVocabulary> = emptyList())

@JsonClass(generateAdapter = true)
data class ImportedMetadata(val userPersonaPreference: String? = null)

@JsonClass(generateAdapter = true)
data class ImportedModel(
    val exportMetadata: ImportedMetadata? = null,
    val typingPatterns: ImportedTypingPatterns? = null,
    val correctionHistory: List<ImportedCorrection> = emptyList(),
    val writingLogs: List<ImportedLog> = emptyList()
)

object PersonalModelSerializer {

    private val EMAIL_REGEX = """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""".toRegex()
    private val PHONE_REGEX = """(?:\+?\d{1,3}[-.\s]?)?(?:\(?\d{3}\)?[-.\s]?\d{3}|\b\d{3})[-.\s]?\d{4}\b""".toRegex()
    private val CC_REGEX = """\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b""".toRegex()
    private val SSN_REGEX = """\b\d{3}-\d{2}-\d{4}\b""".toRegex()
    private val IP_REGEX = """\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b""".toRegex()
    private val URL_REGEX = """\b(?:https?|ftp)://[^\s/$.?#].[^\s]*\b""".toRegex()
    private val NUM_ID_REGEX = """\b\d{6,}\b""".toRegex()

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
        val emailMatches = EMAIL_REGEX.findAll(sanitized).toList()
        if (emailMatches.isNotEmpty()) {
            emails += emailMatches.size
            sanitized = sanitized.replace(EMAIL_REGEX, "[REDACTED_EMAIL]")
        }

        // 2. Phone Number Redaction (e.g., +1-555-0199, (555) 555-0199, 555-555-0199)
        val phoneMatches = PHONE_REGEX.findAll(sanitized).toList()
        if (phoneMatches.isNotEmpty()) {
            phones += phoneMatches.size
            sanitized = sanitized.replace(PHONE_REGEX, "[REDACTED_PHONE]")
        }

        // 3. Credit Card or SSN Redaction
        val ccMatches = CC_REGEX.findAll(sanitized).toList()
        if (ccMatches.isNotEmpty()) {
            cards += ccMatches.size
            sanitized = sanitized.replace(CC_REGEX, "[REDACTED_FINANCIAL]")
        }

        val ssnMatches = SSN_REGEX.findAll(sanitized).toList()
        if (ssnMatches.isNotEmpty()) {
            cards += ssnMatches.size
            sanitized = sanitized.replace(SSN_REGEX, "[REDACTED_FINANCIAL]")
        }

        // 4. IP Address Redaction
        val ipMatches = IP_REGEX.findAll(sanitized).toList()
        if (ipMatches.isNotEmpty()) {
            ips += ipMatches.size
            sanitized = sanitized.replace(IP_REGEX, "[REDACTED_IP]")
        }

        // 5. URL Redaction
        val urlMatches = URL_REGEX.findAll(sanitized).toList()
        if (urlMatches.isNotEmpty()) {
            urls += urlMatches.size
            sanitized = sanitized.replace(URL_REGEX, "[REDACTED_URL]")
        }

        // 6. Generic Numeric IDs (e.g. solid sequences of 6+ digits, like private tokens/accounts)
        val numIdMatches = NUM_ID_REGEX.findAll(sanitized).toList()
        if (numIdMatches.isNotEmpty()) {
            numerics += numIdMatches.size
            sanitized = sanitized.replace(NUM_ID_REGEX, "[REDACTED_NUMERIC_ID]")
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

    /**
     * Parses a previously exported personalization payload — either raw JSON or a
     * Base64 Cipher Block. Returns null when the content is not a valid export.
     */
    fun parseImport(content: String): ImportedModel? {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return null

        val json = if (trimmed.startsWith("{")) {
            trimmed
        } else {
            try {
                String(Base64.decode(trimmed, Base64.NO_WRAP), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                return null
            }
        }

        return try {
            Moshi.Builder().build().adapter(ImportedModel::class.java).fromJson(json)
        } catch (e: Exception) {
            null
        }
    }
}
