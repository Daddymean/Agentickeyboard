package io.github.daddymean.agentickeyboard.twin

import io.github.daddymean.agentickeyboard.util.CloudTextSanitizer
import io.github.daddymean.agentickeyboard.util.SecretTokenPatterns

/**
 * KEYBOARD-024: what may enter the twin store.
 *
 * - Any credential (the #131 secret rules: tokens, private keys, `password=` style
 *   values, bearer tokens) drops the **whole** message. A secret is never stored, even
 *   redacted, so its surrounding words cannot hint at it either.
 * - Everything else the cloud redactor masks (e-mail addresses, card and ID numbers,
 *   SSNs, phone numbers, IP addresses, URLs) is replaced by its marker, so the
 *   value is never stored but the shape of the sentence is.
 * - Overlong text is cut at [MAX_CHARS]; text that is only markers is dropped (emoji-only messages are kept).
 */
object TwinTextFilter {
    const val MAX_CHARS = 4_000
    private const val SECRET_MARKER = "[REDACTED_SECRET]"
    private val markers = Regex("\\[REDACTED_[A-Z_]+]")

    fun prepare(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        if (SecretTokenPatterns.containsToken(trimmed) ||
            SecretTokenPatterns.privateKeyHeader.containsMatchIn(trimmed)) return null
        val sanitized = CloudTextSanitizer.sanitize(trimmed).text
        if (sanitized.contains(SECRET_MARKER)) return null
        if (markers.replace(sanitized, "").isBlank()) return null
        return if (sanitized.length <= MAX_CHARS) sanitized else sanitized.take(MAX_CHARS)
    }
}
