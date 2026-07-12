package io.github.daddymean.agentickeyboard.util

/** Result of sanitizing a cloud-bound request body. */
data class CloudRedactionResult(
    val text: String,
    val replacements: Int
) {
    val changed: Boolean get() = replacements > 0
}

/**
 * Redacts common sensitive values before a request leaves the device.
 *
 * This deliberately operates on the final serialized request body, rather than
 * individual AI actions, so every current and future Gemini request receives the
 * same protection. Replacement markers are plain ASCII and safe inside JSON
 * strings.
 */
object CloudTextSanitizer {
    private data class Rule(val regex: Regex, val replacement: String)

    private val rules = listOf(
        // Explicit credential-like assignments: password=..., api_key: ..., token "..."
        Rule(
            Regex(
                pattern = """(?i)\b(password|passcode|api[_ -]?key|access[_ -]?token|auth[_ -]?token|secret)\b\s*[:=]\s*[\"']?[^\s,;\"'}]+"""
            ),
            replacement = "$1=[REDACTED_SECRET]"
        ),
        Rule(
            Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""),
            "[REDACTED_EMAIL]"
        ),
        // Card-like numbers before phone matching, so a card is not partially consumed as a phone.
        Rule(
            Regex("""\b(?:\d[ -]*?){13,19}\b"""),
            "[REDACTED_FINANCIAL]"
        ),
        Rule(
            Regex("""\b\d{3}-\d{2}-\d{4}\b"""),
            "[REDACTED_SSN]"
        ),
        Rule(
            Regex("""(?<!\d)(?:\+?\d{1,3}[-.\s]?)?(?:\(?\d{3}\)?[-.\s]?)\d{3}[-.\s]?\d{4}(?!\d)"""),
            "[REDACTED_PHONE]"
        ),
        Rule(
            Regex("""\b(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}\b"""),
            "[REDACTED_IP]"
        ),
        Rule(
            Regex("""(?i)\b(?:https?|ftp)://[^\s\"'<>]+"""),
            "[REDACTED_URL]"
        ),
        // Long uninterrupted identifiers such as account, order, claim, or tracking numbers.
        Rule(
            Regex("""\b\d{8,}\b"""),
            "[REDACTED_NUMERIC_ID]"
        )
    )

    fun sanitize(text: String): CloudRedactionResult {
        if (text.isBlank()) return CloudRedactionResult(text, 0)

        var sanitized = text
        var replacements = 0

        for (rule in rules) {
            val count = rule.regex.findAll(sanitized).count()
            if (count > 0) {
                replacements += count
                sanitized = rule.regex.replace(sanitized, rule.replacement)
            }
        }

        return CloudRedactionResult(sanitized, replacements)
    }
}
