package io.github.daddymean.agentickeyboard.util

/**
 * Opt-in pre-send check: when the send-guard setting is on and a draft reads
 * hostile or aggressive, the IME holds the Send action back once and shows an
 * inline "Send anyway?" confirm instead. Entirely local and pure JVM so unit
 * tests exercise it directly — no draft ever leaves the device for this check.
 */
object SendGuard {

    /** True when [text] reads hostile/aggressive enough to pause before sending. */
    fun shouldWarn(text: String): Boolean {
        if (text.isBlank()) return false
        return WritingQualityMeter.risk(text) == WritingQualityMeter.RISK_HIGH
    }
}
