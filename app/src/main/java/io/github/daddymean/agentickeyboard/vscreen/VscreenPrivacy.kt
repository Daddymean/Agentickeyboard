package io.github.daddymean.agentickeyboard.vscreen

import io.github.daddymean.agentickeyboard.twin.TwinPrivacyFilter
import io.github.daddymean.agentickeyboard.util.EditorPrivacy

/**
 * Privacy gate for the Agent-VScreen companion. Reuses [EditorPrivacy] and
 * [TwinPrivacyFilter] so password, OTP, payment, incognito and secure-app
 * targets are refused the same way the keyboard and twin already refuse them.
 *
 * Agent-VScreen itself will type into a password box and only hide the value
 * in its log. This gate must refuse before any request is built.
 */
object VscreenPrivacy {

    enum class Block {
        PASSWORD,
        INCOGNITO,
        SECURE_WINDOW,
        SENSITIVE_HINT,
        ONE_TIME_CODE,
        PAYMENT,
    }

    data class Verdict(val blocked: Boolean, val blocks: Set<Block>)

    fun evaluate(
        targetPackage: String?,
        inputType: Int,
        imeOptions: Int,
        hints: List<String?>,
        secureWindow: Boolean,
        ownPackage: String?,
        secureApps: Set<String>,
    ): Verdict {
        val field = TwinPrivacyFilter.Field(
            inputType = inputType,
            imeOptions = imeOptions,
            hints = hints,
            packageName = targetPackage,
            secureWindow = secureWindow,
        )
        val twin = TwinPrivacyFilter.reasons(field, secureApps, ownPackage)
        val blocks = LinkedHashSet<Block>()
        if (EditorPrivacy.isPasswordInputType(inputType) || TwinPrivacyFilter.Reason.PASSWORD in twin) {
            blocks += Block.PASSWORD
        }
        if (EditorPrivacy.requestsNoPersonalizedLearning(imeOptions) || TwinPrivacyFilter.Reason.INCOGNITO in twin) {
            blocks += Block.INCOGNITO
        }
        if (TwinPrivacyFilter.Reason.SECURE_WINDOW in twin) blocks += Block.SECURE_WINDOW
        if (TwinPrivacyFilter.Reason.SENSITIVE_HINT in twin) blocks += Block.SENSITIVE_HINT
        if (TwinPrivacyFilter.Reason.ONE_TIME_CODE in twin) blocks += Block.ONE_TIME_CODE
        if (isPaymentHint(hints) || isPaymentArg(hints)) blocks += Block.PAYMENT
        return Verdict(blocks.isNotEmpty(), blocks)
    }

    /**
     * Payment / checkout wording TwinPrivacyFilter may not already treat as a
     * card or account hint (e.g. a "Pay now" / "Checkout" button label).
     */
    internal fun isPaymentHint(hints: List<String?>): Boolean {
        val words = hints.filterNotNull().flatMap(::words)
        if (words.any { it in PAYMENT_WORDS }) return true
        return PAYMENT_PHRASES.any { phrase ->
            words.windowed(phrase.size).any { it == phrase }
        }
    }

    /** Same split as TwinPrivacyFilter.words; kept local because that helper is internal. */
    private fun words(raw: String): List<String> =
        raw.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }

    private fun isPaymentArg(hints: List<String?>): Boolean = isPaymentHint(hints)

    private val PAYMENT_WORDS = setOf(
        "payment", "payments", "purchase", "purchases", "checkout", "paypal",
        "venmo", "cashapp", "iban", "wire", "ach",
    )

    private val PAYMENT_PHRASES = listOf(
        listOf("pay", "now"),
        listOf("buy", "now"),
        listOf("place", "order"),
        listOf("add", "card"),
        listOf("bank", "transfer"),
    )
}
