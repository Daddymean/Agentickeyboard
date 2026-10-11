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
 * - A message with an **identifier** (a label such as ID, passport, licence, account,
 *   member or policy plus any digit, or any letters-and-digits token of 6+ characters)
 *   is dropped whole (Codex review on da29f67, r4239989012); see [IdentifierMentions].
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
        if (IdentifierMentions.matches(trimmed)) return null
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
            "\\bpin\\s*(code|number|no\\.?|#|is|was|=|:)|" +
            // "pin for my card is 1234", "pin to the safe: 4471" (Codex review on 6de6407, r4239778219)
            "\\bpins?\\s+(for|to|of|on|at)\\b",
        RegexOption.IGNORE_CASE
    )
    /** "code is 482913", "code: 4471": a code followed by a value. */
    private val codeValue = Regex("\\bcode\\s*(is|was|=|:)?\\s*[A-Za-z]*\\d{3,}", RegexOption.IGNORE_CASE)
    /** "pin/code ... is 1234" within one clause: a PIN or code with a 3+ digit value nearby. */
    private val nearValue = Regex("\\b(pins?|codes?)\\b[^.!?\\n]{0,40}?\\d{3,}", RegexOption.IGNORE_CASE)
    /** The value first: "1234 is the code", "2580 = my pin" (Codex review on 2cfae3f, r4239826927). */
    private val valueThenLabel = Regex("\\d{3,}[^.!?\\n]{0,40}?\\b(pins?|codes?|passcodes?|passwords?|combo|combination)\\b", RegexOption.IGNORE_CASE)

    /**
     * The broadest rule (Codex reviews on a401124 and 7502783, r4239848795 / r4239941592;
     * Hax decisions 2026-10-10 18:37 PT): **any credential word drops the message, digits
     * or not**, in any case, singular, plural or possessive. Whole words by letters, so
     * "pin1234" matches but "Pinterest" does not. Many harmless messages ("I'll code it
     * tonight", "Pin the post") are not learned; dropping is the safe default.
     */
    private val credentialWord = Regex(
        "(?<!\\p{L})(" +
            "code|pin|password|passwd|pwd|pw|passcode|passphrase|passkey|" +
            "combination|combo|otp|2fa|mfa|totp|cvv2?|cvc|csc|" +
            "security\\s+(answer|question)|(seed|recovery|backup)\\s+(phrase|word|code|key)|mnemonic|" +
            "log-?in|logon|sign-?in|username" +
            ")(e?s|['\u2019]s|s['\u2019])?(?!\\p{L})",
        RegexOption.IGNORE_CASE
    )

    fun matches(text: String): Boolean =
        credentialWord.containsMatchIn(text) ||
        labels.containsMatchIn(text) || pinUpper.containsMatchIn(text) ||
            pinLower.containsMatchIn(text) || codeValue.containsMatchIn(text) || nearValue.containsMatchIn(text) ||
            valueThenLabel.containsMatchIn(text)
}

/**
 * KEYBOARD-024: identifiers (Codex review on da29f67, r4239989012). The cloud redactor
 * only masks long digit runs, so short or alphanumeric IDs ("my employee ID is 123456",
 * "passport number AB123456") would be stored. Conservatively, the whole message is
 * dropped when:
 * - an identifier label appears together with **any digit anywhere** in the message; or
 * - any token mixes letters and digits and is 6+ characters long (passport, plate,
 *   booking-reference shapes), labeled or not.
 * Harmless messages ("in case 2 people come", "covid19") are not learned; dropping is
 * the safe default.
 */
internal object IdentifierMentions {
    private val label = Regex(
        "(?<!\\p{L})(" +
            "ids?|identification|identifiers?|identity|passports?|licen[cs]es?|dl|ssns?|" +
            "social\\s+(security|insurance)|national\\s+(insurance|id)|nhs|nino|tin|itin|ein|vat|" +
            "tax\\s+(id|number|code|file|ref)|iban|routing|swift|bic|sort\\s+code|acct|accounts?|" +
            "members?|membership|polic(y|ies)|customers?|patients?|mrn|medical\\s+records?|students?|" +
            "employees?|staff|badges?|serials?|vins?|plates?|registrations?|reg|references?|refs?|" +
            "bookings?|reservations?|confirmations?|tracking|visas?|permits?|green\\s+card|alien|" +
            "cases?|claims?|tickets?|numbers?|nos?\\.|nr\\.?|#" +
            ")(['\u2019]s)?(?!\\p{L})",
        RegexOption.IGNORE_CASE
    )
    private val digit = Regex("\\p{Nd}")
    /** A run of letters and digits, 6+ long, with at least one of each. */
    private val mixedToken = Regex("(?<![\\p{L}\\p{Nd}])(?=[\\p{L}\\p{Nd}]*\\p{L})(?=[\\p{L}\\p{Nd}]*\\p{Nd})[\\p{L}\\p{Nd}]{6,}(?![\\p{L}\\p{Nd}])")

    fun matches(text: String): Boolean =
        (digit.containsMatchIn(text) && label.containsMatchIn(text)) || mixedToken.containsMatchIn(text)
}
