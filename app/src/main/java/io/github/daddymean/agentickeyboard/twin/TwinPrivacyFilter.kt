package io.github.daddymean.agentickeyboard.twin

import android.view.inputmethod.EditorInfo
import io.github.daddymean.agentickeyboard.util.EditorPrivacy

/**
 * KEYBOARD-042: the twin's stricter privacy filter, layered on top of
 * [EditorPrivacy] (password input types and incognito /
 * [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]). When any [Reason] applies the
 * twin captures nothing from the field.
 *
 * Extra signals:
 * - **Secure windows.** A keyboard cannot read another app's FLAG_SECURE flag
 *   (Android exposes no API for it, and we will not capture the screen or use
 *   accessibility to find out). So a window counts as secure when the caller
 *   knows it is (our own FLAG_SECURE twin screens), when it is one of our own
 *   screens, or when its app is on Keith's "never learn here" list (banking and
 *   similar apps that block screenshots).
 * - **Sensitive hints.** Autofill hints (`password`, `creditCardNumber`,
 *   `smsOTPCode`...), the field's hint text, label, field name and private IME
 *   options are matched against password, PIN, one-time-code, card and ID words.
 * - **One-time codes.** A number field whose hint mentions a code.
 *
 * Pattern inspired by the FLAG_SECURE and isPassword guards in
 * jackwener/DSH-Android (Apache-2.0); this is a clean re-implementation.
 * Pure logic, unit-tested without an Android runtime.
 */
object TwinPrivacyFilter {
    enum class Reason { PASSWORD, INCOGNITO, SECURE_WINDOW, SENSITIVE_HINT, ONE_TIME_CODE }

    data class Field(
        val inputType: Int,
        val imeOptions: Int,
        /** Autofill hints, hint text, label, field name, private IME options; any may be null. */
        val hints: List<String?>,
        val packageName: String?,
        /** The caller knows this window is FLAG_SECURE. */
        val secureWindow: Boolean = false
    )

    fun blocksCapture(field: Field, secureApps: Set<String> = emptySet(), ownPackage: String? = null): Boolean =
        reasons(field, secureApps, ownPackage).isNotEmpty()

    fun reasons(field: Field, secureApps: Set<String> = emptySet(), ownPackage: String? = null): Set<Reason> {
        val out = LinkedHashSet<Reason>()
        if (EditorPrivacy.isPasswordInputType(field.inputType)) out += Reason.PASSWORD
        if (EditorPrivacy.requestsNoPersonalizedLearning(field.imeOptions)) out += Reason.INCOGNITO
        val pkg = field.packageName
        if (field.secureWindow || (pkg != null && (pkg == ownPackage || pkg in secureApps))) out += Reason.SECURE_WINDOW
        if (EditorPrivacy.hasSensitiveHint(field.hints)) out += Reason.SENSITIVE_HINT
        if (EditorPrivacy.isOneTimeCode(field.inputType, field.hints)) out += Reason.ONE_TIME_CODE
        return out
    }

    /** Splits camelCase, snake_case, kebab-case and spaces into lower-case words. */
    internal fun words(raw: String): List<String> = EditorPrivacy.words(raw)
}
