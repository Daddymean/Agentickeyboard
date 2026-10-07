package io.github.daddymean.agentickeyboard.util

/**
 * Shapes of well-known bare credentials: tokens that are secret on their own, with no
 * `password=` style label in front of them. Shared by [ClipboardHistoryPolicy] (never
 * retain such a clip) and [CloudTextSanitizer] (never send one to the cloud), so the
 * two paths cannot drift apart.
 */
object SecretTokenPatterns {
    /** PEM private-key header; on its own enough to treat text as a secret. */
    val privateKeyHeader = Regex(
        "-----BEGIN (?:RSA |EC |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY-----",
        RegexOption.IGNORE_CASE
    )

    /**
     * A whole PEM private-key block, header through footer. An unterminated block runs
     * to the end of the text, so a truncated paste is still redacted in full.
     */
    val privateKeyBlock = Regex(
        "-----BEGIN (?:RSA |EC |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY-----[\\s\\S]*?" +
            "(?:-----END (?:RSA |EC |OPENSSH |PGP |ENCRYPTED )?PRIVATE KEY-----|\\z)",
        RegexOption.IGNORE_CASE
    )

    /** `Bearer <token>`; group 1 is the scheme word so it can be kept. */
    val bearer = Regex("\\b(bearer)\\s+[A-Za-z0-9._~+/=-]{12,}", RegexOption.IGNORE_CASE)
    val jwt = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
    val openAiKey = Regex("\\bsk-[A-Za-z0-9_-]{16,}\\b")
    val githubToken = Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b")
    val googleApiKey = Regex("\\bAIza[A-Za-z0-9_-]{20,}\\b")
    val awsAccessKey = Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b")

    /** Bare tokens redacted whole (everything except [bearer], which keeps its scheme). */
    val bareTokens: List<Regex> = listOf(jwt, openAiKey, githubToken, googleApiKey, awsAccessKey)

    fun containsToken(text: String): Boolean =
        bearer.containsMatchIn(text) || bareTokens.any { it.containsMatchIn(text) }
}
