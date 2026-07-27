package io.github.daddymean.agentickeyboard.util

import java.security.MessageDigest
import java.util.Locale

/** Fixed privacy and storage limits for local clipboard history. */
object ClipboardHistoryLimits {
    const val MAX_CONTENT_CHARS = 4_000
    const val MAX_UNPINNED_ITEMS = 20
    const val MAX_PINNED_ITEMS = 10
    const val DEFAULT_RETENTION_DAYS = 7
    const val MIN_RETENTION_DAYS = 1
    const val MAX_RETENTION_DAYS = 30
    const val DAY_MS = 86_400_000L
}

enum class ClipboardRejectReason {
    BLANK,
    TOO_LONG,
    PRIVATE_KEY,
    AUTH_SECRET,
    PASSWORD_OR_PIN,
    ONE_TIME_CODE,
    PAYMENT_CARD,
    PAYMENT_SECURITY_CODE,
    GOVERNMENT_ID,
    RECOVERY_PHRASE
}

sealed interface ClipboardCaptureDecision {
    data class Accept(
        val content: String,
        val contentHash: String
    ) : ClipboardCaptureDecision

    data class Reject(val reason: ClipboardRejectReason) : ClipboardCaptureDecision
}

/**
 * Conservative local-only filter applied before clipboard text can enter Room.
 * It intentionally prefers a false rejection over retaining credential-shaped data.
 */
object ClipboardHistoryPolicy {
    private val privateKey = Regex(
        "-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----",
        RegexOption.IGNORE_CASE
    )
    private val passwordAssignment = Regex(
        "\\b(?:password|passwd|passcode|pin|secret|api[ _-]?key|access[ _-]?token|refresh[ _-]?token)\\b\\s*[:=]\\s*\\S{3,}",
        RegexOption.IGNORE_CASE
    )
    private val oneTimeCode = Regex(
        "\\b(?:otp|one[ -]?time|verification|security|login|authentication)\\s*(?:code)?\\D{0,12}\\b\\d{4,8}\\b",
        RegexOption.IGNORE_CASE
    )
    private val paymentSecurityCode = Regex(
        "\\b(?:cvv|cvc|security code)\\b\\D{0,8}\\b\\d{3,4}\\b",
        RegexOption.IGNORE_CASE
    )
    private val ssn = Regex("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)")
    private val recoveryPhrase = Regex(
        "\\b(?:seed|recovery|mnemonic)\\s+(?:phrase|words?)\\b[\\s:=-]+(?:[a-z]{2,}\\s+){11,23}[a-z]{2,}",
        RegexOption.IGNORE_CASE
    )
    private val bearer = Regex("\\bbearer\\s+[A-Za-z0-9._~+/=-]{12,}", RegexOption.IGNORE_CASE)
    private val jwt = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
    private val openAiKey = Regex("\\bsk-[A-Za-z0-9_-]{16,}\\b")
    private val githubToken = Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b")
    private val googleApiKey = Regex("\\bAIza[A-Za-z0-9_-]{20,}\\b")
    private val awsAccessKey = Regex("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b")
    private val cardCandidate = Regex("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)")

    fun evaluate(raw: String): ClipboardCaptureDecision {
        val content = normalize(raw)
        if (content.isBlank()) return ClipboardCaptureDecision.Reject(ClipboardRejectReason.BLANK)
        if (content.length > ClipboardHistoryLimits.MAX_CONTENT_CHARS) {
            return ClipboardCaptureDecision.Reject(ClipboardRejectReason.TOO_LONG)
        }
        if (privateKey.containsMatchIn(content)) {
            return ClipboardCaptureDecision.Reject(ClipboardRejectReason.PRIVATE_KEY)
        }
        if (
            bearer.containsMatchIn(content) || jwt.containsMatchIn(content) ||
            openAiKey.containsMatchIn(content) || githubToken.containsMatchIn(content) ||
            googleApiKey.containsMatchIn(content) || awsAccessKey.containsMatchIn(content)
        ) {
            return ClipboardCaptureDecision.Reject(ClipboardRejectReason.AUTH_SECRET)
        }
        if (passwordAssignment.containsMatchIn(content)) {
            return ClipboardCaptureDecision.Reject(ClipboardRejectReason.PASSWORD_OR_PIN)
        }
        if (oneTimeCode.containsMatchIn(content)) {
            return ClipboardCaptureDecision.Reject(ClipboardRejectReason.ONE_TIME_CODE)
        }
        if (paymentSecurityCode.containsMatchIn(content)) {
            return ClipboardCaptureDecision.Reject(ClipboardRejectReason.PAYMENT_SECURITY_CODE)
        }
        if (ssn.containsMatchIn(content)) {
            return ClipboardCaptureDecision.Reject(ClipboardRejectReason.GOVERNMENT_ID)
        }
        if (recoveryPhrase.containsMatchIn(content)) {
            return ClipboardCaptureDecision.Reject(ClipboardRejectReason.RECOVERY_PHRASE)
        }
        if (cardCandidate.findAll(content).any { looksLikePaymentCard(it.value) }) {
            return ClipboardCaptureDecision.Reject(ClipboardRejectReason.PAYMENT_CARD)
        }
        return ClipboardCaptureDecision.Accept(content, sha256(content))
    }

    fun rejectionMessage(reason: ClipboardRejectReason): String = when (reason) {
        ClipboardRejectReason.BLANK -> "Clipboard is empty."
        ClipboardRejectReason.TOO_LONG -> "Clip is too large to retain safely."
        ClipboardRejectReason.PRIVATE_KEY -> "Private-key material was not saved."
        ClipboardRejectReason.AUTH_SECRET -> "Credential-shaped content was not saved."
        ClipboardRejectReason.PASSWORD_OR_PIN -> "A password, PIN, or secret was not saved."
        ClipboardRejectReason.ONE_TIME_CODE -> "A one-time or verification code was not saved."
        ClipboardRejectReason.PAYMENT_CARD -> "Payment-card data was not saved."
        ClipboardRejectReason.PAYMENT_SECURITY_CODE -> "A payment security code was not saved."
        ClipboardRejectReason.GOVERNMENT_ID -> "Government-ID-shaped content was not saved."
        ClipboardRejectReason.RECOVERY_PHRASE -> "A recovery phrase was not saved."
    }

    internal fun normalize(raw: String): String = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()

    private fun looksLikePaymentCard(candidate: String): Boolean {
        val digits = candidate.filter(Char::isDigit)
        if (digits.length !in 13..19 || digits.toSet().size == 1) return false
        var sum = 0
        var doubleDigit = false
        for (index in digits.indices.reversed()) {
            var value = digits[index].digitToInt()
            if (doubleDigit) {
                value *= 2
                if (value > 9) value -= 9
            }
            sum += value
            doubleDigit = !doubleDigit
        }
        return sum % 10 == 0
    }

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff) }
}

data class ClipboardRetentionRecord(
    val id: Int,
    val pinned: Boolean,
    val lastSeenAt: Long
)

data class ClipboardPrunePlan(
    val expiredIds: Set<Int>,
    val overflowIds: Set<Int>
) {
    val idsToDelete: Set<Int> = expiredIds + overflowIds
}

/** Deterministic retention planning so database pruning can be tested without Android or Room. */
object ClipboardHistoryPruner {
    fun plan(
        records: List<ClipboardRetentionRecord>,
        now: Long,
        retentionDays: Int,
        maxUnpinnedItems: Int = ClipboardHistoryLimits.MAX_UNPINNED_ITEMS
    ): ClipboardPrunePlan {
        val days = retentionDays.coerceIn(
            ClipboardHistoryLimits.MIN_RETENTION_DAYS,
            ClipboardHistoryLimits.MAX_RETENTION_DAYS
        )
        val cutoff = now - days * ClipboardHistoryLimits.DAY_MS
        val unpinned = records.filterNot { it.pinned }
        val expired = unpinned.filter { it.lastSeenAt < cutoff }.mapTo(linkedSetOf()) { it.id }
        val survivors = unpinned
            .filterNot { it.id in expired }
            .sortedWith(compareByDescending<ClipboardRetentionRecord> { it.lastSeenAt }.thenByDescending { it.id })
        val overflow = survivors
            .drop(maxUnpinnedItems.coerceAtLeast(0))
            .mapTo(linkedSetOf()) { it.id }
        return ClipboardPrunePlan(expired, overflow)
    }
}
