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
 * The patterns are written for plain text. `CloudRequestRedactor` applies them at
 * the network boundary, so every current and future Gemini request receives the
 * same protection: to each decoded string value of a JSON body (never to the
 * escaped JSON text), or to the whole body when it is not JSON. Replacement
 * markers are plain ASCII.
 */
object CloudTextSanitizer {
    private data class Rule(val regex: Regex, val replacement: String)

    private val rules = listOf(
        // Multi-part secrets first: a PEM block and `Bearer <token>`. The assignment rule
        // below takes only the first word of an unquoted value, so `secret=-----BEGIN …`
        // or `access_token: Bearer …` would otherwise consume just the header or the
        // scheme word and leave the key body or token in the request.
        Rule(SecretTokenPatterns.privateKeyBlock, "[REDACTED_SECRET]"),
        Rule(SecretTokenPatterns.bearer, "\$1 [REDACTED_SECRET]"),
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
            replacement = "\$1=[REDACTED_SECRET]"
        ),
        // Bare credentials that carry no `password=` label: JWTs and provider keys
        // (Google, GitHub, GitLab, Slack, Stripe, OpenAI, AWS). Shared with the
        // clipboard-history filter. They
        // run before the numeric rules so digits inside a token are not half-matched
        // as a phone number, leaving the rest of the token behind.
        *SecretTokenPatterns.bareTokens.map { Rule(it, "[REDACTED_SECRET]") }.toTypedArray(),
        Rule(
            Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"""),
            "[REDACTED_EMAIL]"
        ),
        // Card-like numbers before other numeric rules, so a card is not partially consumed.
        Rule(
            Regex("""\b(?:\d[ -]*?){13,19}\b"""),
            "[REDACTED_FINANCIAL]"
        ),
        Rule(
            Regex("""\b\d{3}-\d{2}-\d{4}\b"""),
            "[REDACTED_SSN]"
        ),
        // Long uninterrupted identifiers such as account, order, claim, or tracking numbers.
        // This runs before phone matching so an order number is not mislabeled as a phone.
        Rule(
            Regex("""\b\d{8,}\b"""),
            "[REDACTED_NUMERIC_ID]"
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
