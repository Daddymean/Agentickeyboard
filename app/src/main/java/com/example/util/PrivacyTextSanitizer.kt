package com.example.util

/**
 * Shared text sanitizer used before writing keyboard-derived data to disk or
 * sending user text to a cloud model. It favors conservative redaction because
 * keyboard input can contain passwords, codes, addresses, medical details, legal
 * text, and other high-risk fragments.
 */
object PrivacyTextSanitizer {

    data class Stats(
        val emailsRedacted: Int = 0,
        val phonesRedacted: Int = 0,
        val financialsRedacted: Int = 0,
        val urlsRedacted: Int = 0,
        val ipAddressesRedacted: Int = 0,
        val numericIdsRedacted: Int = 0,
        val totalRedactions: Int = 0
    )

    data class Result(
        val sanitized: String,
        val stats: Stats
    )

    private val emailRegex = """[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""".toRegex()
    private val phoneRegex = """(?:\+?\d{1,3}[-.\s]?)?(?:\(?\d{3}\)?[-.\s]?\d{3}|\b\d{3})[-.\s]?\d{4}\b""".toRegex()
    private val cardRegex = """\b\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}\b""".toRegex()
    private val ssnRegex = """\b\d{3}-\d{2}-\d{4}\b""".toRegex()
    private val ipRegex = """\b(?:[0-9]{1,3}\.){3}[0-9]{1,3}\b""".toRegex()
    private val urlRegex = """\b(?:https?|ftp)://[^\s/$.?#].[^\s]*\b""".toRegex()
    private val numericIdRegex = """\b\d{6,}\b""".toRegex()

    fun sanitizeText(text: String): Result {
        if (text.isBlank()) return Result(text, Stats())

        var sanitized = text
        var emails = 0
        var phones = 0
        var financials = 0
        var urls = 0
        var ips = 0
        var numericIds = 0

        emails = emailRegex.findAll(sanitized).count()
        if (emails > 0) sanitized = sanitized.replace(emailRegex, "[REDACTED_EMAIL]")

        phones = phoneRegex.findAll(sanitized).count()
        if (phones > 0) sanitized = sanitized.replace(phoneRegex, "[REDACTED_PHONE]")

        val cards = cardRegex.findAll(sanitized).count()
        val ssns = ssnRegex.findAll(sanitized).count()
        financials = cards + ssns
        if (cards > 0) sanitized = sanitized.replace(cardRegex, "[REDACTED_FINANCIAL]")
        if (ssns > 0) sanitized = sanitized.replace(ssnRegex, "[REDACTED_FINANCIAL]")

        ips = ipRegex.findAll(sanitized).count()
        if (ips > 0) sanitized = sanitized.replace(ipRegex, "[REDACTED_IP]")

        urls = urlRegex.findAll(sanitized).count()
        if (urls > 0) sanitized = sanitized.replace(urlRegex, "[REDACTED_URL]")

        numericIds = numericIdRegex.findAll(sanitized).count()
        if (numericIds > 0) sanitized = sanitized.replace(numericIdRegex, "[REDACTED_NUMERIC_ID]")

        val total = emails + phones + financials + urls + ips + numericIds
        return Result(
            sanitized = sanitized,
            stats = Stats(
                emailsRedacted = emails,
                phonesRedacted = phones,
                financialsRedacted = financials,
                urlsRedacted = urls,
                ipAddressesRedacted = ips,
                numericIdsRedacted = numericIds,
                totalRedactions = total
            )
        )
    }

    fun sanitizeForCloud(text: String, maxChars: Int): String {
        return sanitizeText(text).sanitized.take(maxChars)
    }
}
