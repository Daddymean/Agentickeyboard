package io.github.daddymean.agentickeyboard.util

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Decides whether an editor must be handled as sensitive: no learning, no
 * history, no clipboard capture and no AI. Password-type fields are always
 * sensitive. So is any editor that sets [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING],
 * the flag browsers' incognito tabs and privacy-conscious apps use to ask the
 * keyboard not to learn from or remember what is typed (KEYBOARD-004).
 *
 * Pure integer logic so it can be unit-tested without an Android runtime.
 */
object EditorPrivacy {

    fun isSensitiveEditor(inputType: Int, imeOptions: Int): Boolean =
        isPasswordInputType(inputType) || requestsNoPersonalizedLearning(imeOptions)

    /**
     * The keyboard AI / learning path's full check: password or incognito, or
     * field metadata ([hints]: autofill hints, hint text, label, field name,
     * private IME options) that names a password, PIN, one-time code, card or
     * ID number. Shared with the twin's [io.github.daddymean.agentickeyboard.twin.TwinPrivacyFilter]
     * so both use one word list. Always on; there is no setting that weakens it.
     */
    fun isSensitiveEditor(inputType: Int, imeOptions: Int, hints: List<String?>): Boolean =
        isSensitiveEditor(inputType, imeOptions) || hasSensitiveHint(hints) || isOneTimeCode(inputType, hints)

    /** Any hint names a password, PIN, one-time code, card or ID number. */
    fun hasSensitiveHint(hints: List<String?>): Boolean {
        val w = hintWords(hints)
        return w.any { it in SENSITIVE_WORDS } || SENSITIVE_PHRASES.any { p -> w.windowed(p.size).any { it == p } }
    }

    /** A number field whose hint mentions a code (SMS / authenticator codes). */
    fun isOneTimeCode(inputType: Int, hints: List<String?>): Boolean =
        (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
            hintWords(hints).any { it == "code" || it == "codes" }

    private fun hintWords(hints: List<String?>): List<String> = hints.filterNotNull().flatMap(::words)

    /** Splits camelCase, snake_case, kebab-case and spaces into lower-case words. */
    fun words(raw: String): List<String> =
        raw.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }

    private val SENSITIVE_WORDS = setOf(
        "password", "passwd", "passcode", "passphrase", "pin", "otp", "totp", "cvv", "cvc", "csc",
        "ssn", "iban", "creditcard", "cardnumber", "onetimecode", "mfa", "2fa"
    )

    private val SENSITIVE_PHRASES = listOf(
        listOf("credit", "card"), listOf("card", "number"), listOf("cc", "number"), listOf("cc", "csc"),
        listOf("security", "code"), listOf("verification", "code"), listOf("one", "time"),
        listOf("expiration", "date"), listOf("social", "security"), listOf("routing", "number"),
        listOf("account", "number"), listOf("sms", "otp")
    )

    fun requestsNoPersonalizedLearning(imeOptions: Int): Boolean =
        (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0

    fun isPasswordInputType(inputType: Int): Boolean {
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return when (inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT ->
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
    }
}
