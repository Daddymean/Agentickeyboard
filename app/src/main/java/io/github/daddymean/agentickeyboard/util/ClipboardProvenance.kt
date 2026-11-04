package io.github.daddymean.agentickeyboard.util

import java.security.MessageDigest

/** Payment destinations that clipboard hijackers ("clippers") swap between copy and paste. */
enum class AddressKind(val label: String) { IBAN("IBAN"), BTC("Bitcoin address"), ETH("Ethereum address"), SOL("Solana address") }

/**
 * Clipboard provenance for the keyboard's own paste path.
 *
 * Each time the keyboard legitimately reads the clipboard (opted-in clipboard
 * history capture while it is in the foreground) it records *where* and *when* it
 * saw an address-like clip: its kind, a SHA-256 fingerprint, the editor's package
 * and a timestamp. No clip text is kept; observations live in memory only and are
 * forgotten when the process dies.
 *
 * When the user pastes through the keyboard (the 📋 chip) and the clip is the
 * same kind of address but a different value than the one seen recently, the
 * keyboard warns before inserting: that is the signature of clipper malware.
 * Android 10+ blocks background clipboard reads, so this only uses reads the IME
 * already makes in the foreground.
 */
class ClipboardProvenance(
    private val windowMillis: Long = 30 * 60 * 1000L,
    private val maxObservations: Int = 8,
) {
    data class Observation(val kind: AddressKind, val fingerprint: String, val sourcePackage: String?, val atMillis: Long)

    sealed interface Verdict {
        object Ok : Verdict
        data class Changed(
            val kind: AddressKind,
            val seenInPackage: String?,
            val minutesAgo: Long,
            val preview: String,
        ) : Verdict {
            val message: String
                get() = "⚠️ This ${kind.label} ($preview) isn't the one copied " +
                    (if (minutesAgo < 1) "just now" else "$minutesAgo min ago") +
                    (seenInPackage?.let { " in ${it.substringAfterLast('.')}" } ?: "") +
                    ". Check every character before sending."
        }
    }

    private val observations = ArrayDeque<Observation>()

    /** Records an address-like clip. Ignored for sensitive clips or editors. */
    fun observe(text: String?, sourcePackage: String?, nowMillis: Long, sensitive: Boolean) {
        if (sensitive || text == null) return
        val kind = detect(text) ?: return
        val fp = fingerprint(text)
        if (observations.lastOrNull()?.fingerprint == fp) return
        observations.addLast(Observation(kind, fp, sourcePackage, nowMillis))
        while (observations.size > maxObservations) observations.removeFirst()
    }

    /** Checks a clip about to be pasted through the keyboard. */
    fun checkPaste(text: String?, nowMillis: Long): Verdict {
        if (text == null) return Verdict.Ok
        val kind = detect(text) ?: return Verdict.Ok
        val fp = fingerprint(text)
        val recent = observations.filter { it.kind == kind && nowMillis - it.atMillis in 0..windowMillis }
        if (recent.isEmpty() || recent.any { it.fingerprint == fp }) return Verdict.Ok
        val last = recent.last()
        return Verdict.Changed(kind, last.sourcePackage, (nowMillis - last.atMillis) / 60_000, preview(text))
    }

    fun clear() = observations.clear()

    companion object {
        private val IBAN = Regex("^[A-Z]{2}\\d{2}[A-Z0-9]{11,30}$")
        private val BTC = Regex("^(bc1[02-9ac-hj-np-z]{11,71}|[13][1-9A-HJ-NP-Za-km-z]{25,34})$")
        private val ETH = Regex("^0x[0-9a-fA-F]{40}$")
        private val SOL = Regex("^[1-9A-HJ-NP-Za-km-z]{32,44}$")

        fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), "")

        fun detect(text: String): AddressKind? {
            val t = normalize(text)
            if (t.length > 100) return null
            return when {
                ETH.matches(t) -> AddressKind.ETH
                BTC.matches(t) -> AddressKind.BTC
                IBAN.matches(t.uppercase()) && t.uppercase() == t -> AddressKind.IBAN
                SOL.matches(t) -> AddressKind.SOL
                else -> null
            }
        }

        fun fingerprint(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(normalize(text).toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** First and last four characters, enough to compare by eye. */
        fun preview(text: String): String = normalize(text).let { if (it.length <= 10) it else it.take(4) + "…" + it.takeLast(4) }
    }
}
