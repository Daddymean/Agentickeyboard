package io.github.daddymean.agentickeyboard.util

/** Categories of sensitive values recognized by [CloudTextSanitizer]. */
enum class RedactionCategory(val singular: String, val plural: String) {
    SECRET("secret", "secrets"),
    EMAIL("email", "emails"),
    FINANCIAL("card", "cards"),
    SSN("SSN", "SSNs"),
    NUMERIC_ID("ID", "IDs"),
    PHONE("phone", "phones"),
    IP("IP", "IPs"),
    URL("URL", "URLs")
}

/** Result of sanitizing text with per-category counts (never retaining raw sensitive values). */
data class DetailedRedactionResult(
    val text: String,
    val replacements: Int,
    val countsByCategory: Map<RedactionCategory, Int>
) {
    val changed: Boolean get() = replacements > 0

    /** Compact, leak-free label for UI alerts (e.g. "1 email" or "3 items (secret, email, phone)"). */
    fun summaryLabel(): String {
        if (replacements <= 0) return "0 items"
        val nonZero = countsByCategory.entries.filter { it.value > 0 }
        if (nonZero.size == 1) {
            val (category, count) = nonZero.first()
            return "$count ${if (count == 1) category.singular else category.plural}"
        }
        val names = nonZero.joinToString(", ") { it.key.singular }
        val itemWord = if (replacements == 1) "item" else "items"
        return if (names.isEmpty()) "$replacements $itemWord" else "$replacements $itemWord ($names)"
    }
}

/** Result of sanitizing a cloud-bound request body. */
data class CloudRedactionResult(
    val text: String,
    val replacements: Int
) {
    val changed: Boolean get() = replacements > 0
}

/**
 * Redacts common sensitive values before a request leaves the device, and powers
 * explicit on-device privacy scrubbing (`/redact`, Scrub PII, Safe Paste).
 *
 * The patterns are written for plain text. `CloudRequestRedactor` applies them at
 * the network boundary, so every current and future Gemini request receives the
 * same protection: to each decoded string value of a JSON body (never to the
 * escaped JSON text), or to the whole body when it is not JSON. Replacement
 * markers are plain ASCII.
 */
object CloudTextSanitizer {
    private data class Rule(
        val regex: Regex,
        val replacement: String,
        val category: RedactionCategory
    )

    private val rules = listOf(
        // Multi-part secrets first: a PEM block and `Bearer <token>`. The assignment rule
        // below takes only the first word of an unquoted value, so `secret=-----BEGIN …`
        // or `access_token: Bearer …` would otherwise consume just the header or the
        // scheme word and leave the key body or token in the request.
        Rule(SecretTokenPatterns.privateKeyBlock, "[REDACTED_SECRET]", RedactionCategory.SECRET),
        Rule(SecretTokenPatterns.bearer, "\$1 [REDACTED_SECRET]", RedactionCategory.SECRET),
        // Explicit credential-like assignments: password=..., api_key: ..., token "...".
        // A value wrapped in matching quotes is consumed together with both quotes, so
        // `password: "hunter2"` becomes `password=[REDACTED_SECRET]` with no stray `"`.
        // A quoted value may contain spaces but not a line break, and is capped so an
        // unbalanced quote cannot swallow a whole paragraph; an unterminated quote falls
        // back to the bare-token form, which drops just the opening quote.
        Rule(
            Regex(
                pattern = """(?i)\b(password|passcode|api[_ -]?key|access[_ -]?token|auth[_ -]?token|secret)\b\s*[:=]\s*(?:"[^"\r\n]{1,256}"|'[^'\r\n]{1,256}'|[\"']?[^\s,;\"'}]+)"""
            ),
            replacement = "\$1=[REDACTED_SECRET]",
            category = RedactionCategory.SECRET
        ),
        // Bare credentials that carry no `password=` label: JWTs and provider keys
        // (Google, GitHub, GitLab, Slack, Stripe, OpenAI, AWS). Shared with the
        // clipboard-history filter. They
        // run before the numeric rules so digits inside a token are not half-matched
        // as a phone number, leaving the rest of the token behind.
        *SecretTokenPatterns.bareTokens
            .map { Rule(it, "[REDACTED_SECRET]", RedactionCategory.SECRET) }
            .toTypedArray(),
        Rule(
            Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""),
            "[REDACTED_EMAIL]",
            RedactionCategory.EMAIL
        ),
        // Card-like numbers before other numeric rules, so a card is not partially consumed.
        Rule(
            Regex("""\b(?:\d[ -]*?){13,19}\b"""),
            "[REDACTED_FINANCIAL]",
            RedactionCategory.FINANCIAL
        ),
        Rule(
            Regex("""\b\d{3}-\d{2}-\d{4}\b"""),
            "[REDACTED_SSN]",
            RedactionCategory.SSN
        ),
        // Long uninterrupted identifiers such as account, order, claim, or tracking numbers.
        // This runs before phone matching so an order number is not mislabeled as a phone.
        Rule(
            Regex("""\b\d{8,}\b"""),
            "[REDACTED_NUMERIC_ID]",
            RedactionCategory.NUMERIC_ID
        ),
        Rule(
            Regex("""(?<!\d)(?:\+?\d{1,3}[-.\s]?)?(?:\(?\d{3}\)?[-.\s]?)\d{3}[-.\s]?\d{4}(?!\d)"""),
            "[REDACTED_PHONE]",
            RedactionCategory.PHONE
        ),
        Rule(
            Regex("""\b(?:25[0-5]|2[0-4]\d|1?\d?\d)(?:\.(?:25[0-5]|2[0-4]\d|1?\d?\d)){3}\b"""),
            "[REDACTED_IP]",
            RedactionCategory.IP
        ),
        Rule(
            Regex("""(?i)\b(?:https?|ftp)://[^\s\"'<>]+"""),
            "[REDACTED_URL]",
            RedactionCategory.URL
        )
    )

    fun sanitizeDetailed(text: String): DetailedRedactionResult {
        if (text.isBlank()) return DetailedRedactionResult(text, 0, emptyMap())

        var sanitized = text
        var replacements = 0
        val counts = LinkedHashMap<RedactionCategory, Int>()

        for (rule in rules) {
            val count = rule.regex.findAll(sanitized).count()
            if (count > 0) {
                replacements += count
                counts[rule.category] = (counts[rule.category] ?: 0) + count
                sanitized = rule.regex.replace(sanitized, rule.replacement)
            }
        }

        return DetailedRedactionResult(sanitized, replacements, counts)
    }

    fun sanitize(text: String): CloudRedactionResult {
        val detailed = sanitizeDetailed(text)
        return CloudRedactionResult(detailed.text, detailed.replacements)
    }
}
