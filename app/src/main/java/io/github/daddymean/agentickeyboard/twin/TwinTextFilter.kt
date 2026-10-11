package io.github.daddymean.agentickeyboard.twin

import io.github.daddymean.agentickeyboard.util.CloudTextSanitizer
import io.github.daddymean.agentickeyboard.util.SecretTokenPatterns

/**
 * KEYBOARD-024: what may enter the twin store.
 *
 * - Any credential (the #131 secret rules: tokens, private keys, `password=` style
 *   values, bearer tokens) drops the **whole** message. A secret is never stored, even
 *   redacted, so its surrounding words cannot hint at it either.
 * - A message that **names a credential** in plain words ("my password is ...", "the
 *   PIN is ...", "code: 482913", a seed phrase, an OTP) is dropped whole as well
 *   (Codex security review on 43a7b02, r4239700720). The value itself can't be told
 *   apart from ordinary words, so the label decides, and when in doubt the message
 *   is not kept.
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
        if (CredentialMentions.matches(trimmed)) return null
        if (markers.replace(sanitized, "").isBlank()) return null
        return if (sanitized.length <= MAX_CHARS) sanitized else sanitized.take(MAX_CHARS)
    }
}

/**
 * KEYBOARD-024: plain-language credential mentions. Deliberately broad; a false
 * positive only means one message isn't learned.
 */
internal object CredentialMentions {
    private val labels = Regex(
        "\\b(" +
            "pass(word|wd|code|phrase|key)s?|pwd|pw|" +
            "wi-?fi\\s+(key|code)|" +
            "otp|2fa|mfa|totp|one[- ]time\\s+(code|pass\\w*)|" +
            "(verification|security|auth(entication)?|login|access|recovery|backup|reset|confirmation)\\s+(code|answer|question|key|phrase|pin)s?|" +
            "seed\\s+phrase|recovery\\s+phrase|mnemonic|secret\\s+(key|phrase|code|answer)|private\\s+key|api\\s*key|" +
            "cvv2?|cvc|csc|" +
            "combo|combination|(lock|door|gate|garage|alarm|safe|entry|building|keypad)\\s+code" +
            ")\\b",
        RegexOption.IGNORE_CASE
    )
    /** PIN in capitals anywhere, or "pin" used as a credential. */
    private val pinUpper = Regex("\\bPINs?\\b")
    private val pinLower = Regex(
        "\\b(my|the|your|his|her|our|their|card|bank|debit|atm|phone|sim|door|gate|garage|alarm|new|old)\\s+pin\\b|" +
            "\\bpin\\s*(code|number|no\\.?|#|is|was|=|:)",
        RegexOption.IGNORE_CASE
    )
    /** "code is 482913", "code: 4471": a code followed by a value. */
    private val codeValue = Regex("\\bcode\\s*(is|was|=|:)?\\s*[A-Za-z]*\\d{3,}", RegexOption.IGNORE_CASE)

    fun matches(text: String): Boolean =
        labels.containsMatchIn(text) || pinUpper.containsMatchIn(text) ||
            pinLower.containsMatchIn(text) || codeValue.containsMatchIn(text)
}
