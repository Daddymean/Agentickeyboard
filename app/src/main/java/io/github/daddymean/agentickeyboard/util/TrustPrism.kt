package io.github.daddymean.agentickeyboard.util

/** The privacy path currently governing keyboard AI actions. */
enum class TrustPrismMode {
    SECURE_FIELD,
    OFFLINE_LOCAL,
    CLOUD_REDACTED,
    CLOUD_UNPROTECTED
}

/** Human-readable Trust Prism state for the keyboard and companion app. */
data class TrustPrismStatus(
    val mode: TrustPrismMode,
    val icon: String,
    val label: String,
    val detail: String,
    val isProtected: Boolean
)

/**
 * Resolves the visible privacy state from the same routing facts that govern AI.
 * Secure fields always win, followed by explicit offline mode, then cloud policy.
 */
object TrustPrism {
    fun resolve(
        isOfflineMode: Boolean,
        isSensitiveField: Boolean,
        cloudRedactionEnabled: Boolean
    ): TrustPrismStatus = when {
        isSensitiveField -> TrustPrismStatus(
            mode = TrustPrismMode.SECURE_FIELD,
            icon = "🔒",
            label = "Secure field",
            detail = "AI and learning are blocked.",
            isProtected = true
        )

        isOfflineMode -> TrustPrismStatus(
            mode = TrustPrismMode.OFFLINE_LOCAL,
            icon = "📵",
            label = "On-device only",
            detail = "Cloud calls are blocked.",
            isProtected = true
        )

        cloudRedactionEnabled -> TrustPrismStatus(
            mode = TrustPrismMode.CLOUD_REDACTED,
            icon = "🛡️",
            label = "Cloud • redacted",
            detail = "Sensitive-looking values are replaced before transmission.",
            isProtected = true
        )

        else -> TrustPrismStatus(
            mode = TrustPrismMode.CLOUD_UNPROTECTED,
            icon = "⚠️",
            label = "Cloud • unredacted",
            detail = "Cloud requests are not being sanitized.",
            isProtected = false
        )
    }
}
