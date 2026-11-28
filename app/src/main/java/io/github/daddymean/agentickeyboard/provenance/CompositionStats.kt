package io.github.daddymean.agentickeyboard.provenance

import kotlin.math.sqrt

/**
 * Proof of Thought spec §5: how a draft was written, as rolling counters only.
 * No keystroke timestamps or intervals are kept; the rhythm is folded into a
 * running mean and variance (Welford) as each key arrives. Memory-only, per field.
 */
class CompositionStats {

    private var startedAt = 0L
    private var lastKeyAt = -1L
    private var gaps = 0L
    private var gapMean = 0.0
    private var gapM2 = 0.0

    var typedKeys = 0
        private set
    var deletedChars = 0
        private set
    var nonlinearEdits = 0
        private set
    var longPauses = 0
        private set

    fun start(nowMillis: Long) {
        clear()
        startedAt = nowMillis
    }

    fun clear() {
        startedAt = 0L
        lastKeyAt = -1L
        gaps = 0L
        gapMean = 0.0
        gapM2 = 0.0
        typedKeys = 0
        deletedChars = 0
        nonlinearEdits = 0
        longPauses = 0
    }

    fun typedKey(nowMillis: Long) {
        typedKeys++
        val previous = lastKeyAt
        lastKeyAt = nowMillis
        if (previous < 0) return
        val gap = (nowMillis - previous).coerceAtLeast(0L)
        if (gap > LONG_PAUSE_MILLIS) {
            longPauses++
            return
        }
        gaps++
        val delta = gap - gapMean
        gapMean += delta / gaps
        gapM2 += delta * (gap - gapMean)
    }

    fun deleted(chars: Int) {
        if (chars > 0) deletedChars += chars
    }

    /** An edit away from the end of the text (going back to revise). */
    fun nonlinearEdit() {
        nonlinearEdits++
    }

    /** Coefficient of variation of the gaps between keys under [LONG_PAUSE_MILLIS]; null below 8 gaps. */
    fun rhythmCv(): Double? {
        if (gaps < MIN_GAPS || gapMean <= 0.0) return null
        return sqrt(gapM2 / (gaps - 1)) / gapMean
    }

    /** The bucketed values a receipt may carry (spec §5.2); [finalChars] is the field length. */
    fun snapshot(finalChars: Int, nowMillis: Long): Snapshot {
        val per100 = if (finalChars > 0) 100.0 / finalChars else 0.0
        val minutes = (nowMillis - startedAt).coerceAtLeast(0L) / 60_000.0
        return Snapshot(
            revision = bucket(if (finalChars > 0) deletedChars.toDouble() / finalChars else 0.0, REVISION),
            nonlinear = bucket(nonlinearEdits * per100, NONLINEAR),
            longPauses = bucket(longPauses * per100, LONG_PAUSES),
            rhythm = rhythmCv()?.let { bucket(it, RHYTHM) },
            sessionLength = bucket(minutes, SESSION_MINUTES)
        )
    }

    enum class Level { LOW, MID, HIGH, VERY_HIGH }

    data class Snapshot(
        val revision: Level,
        val nonlinear: Level,
        val longPauses: Level,
        /** Null when too few keys were typed to say. */
        val rhythm: Level?,
        val sessionLength: Level
    )

    companion object {
        /** Bucket thresholds, versioned in the receipt as `stats_v`; retuned in slice 4. */
        const val STATS_VERSION = 1
        const val LONG_PAUSE_MILLIS = 2_000L
        private const val MIN_GAPS = 8
        private val REVISION = doubleArrayOf(0.05, 0.2, 0.5)
        private val NONLINEAR = doubleArrayOf(0.5, 2.0, 5.0)
        private val LONG_PAUSES = doubleArrayOf(0.5, 2.0, 5.0)
        private val RHYTHM = doubleArrayOf(0.35, 0.7, 1.2)
        private val SESSION_MINUTES = doubleArrayOf(1.0, 5.0, 20.0)

        fun bucket(value: Double, thresholds: DoubleArray): Level {
            val index = thresholds.indexOfFirst { value < it }
            return Level.entries[if (index < 0) thresholds.size else index]
        }
    }
}
