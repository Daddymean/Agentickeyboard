package io.github.daddymean.agentickeyboard.util

/**
 * How long the keyboard's own work takes on this device, for the usage dashboard.
 *
 * It holds durations only: no text, no key identities, no app names. Samples
 * live in memory (the most recent [WINDOW] per metric), are never written
 * anywhere and are gone when the keyboard process ends.
 */
object LatencyMeter {
    enum class Metric(val label: String) {
        /** Handing one key's text to the app being typed into. */
        KEY("Key tap"),
        /** Deciding what a finished word becomes at space: expansions, learned rules, auto-fix. */
        WORD_COMMIT("Space"),
        /** From a text change to the suggestion strip being ready. */
        SUGGESTIONS("Suggestions"),
        /** Turning a swipe path into words. */
        SWIPE("Swipe")
    }

    data class Summary(val count: Int, val medianMs: Double, val p95Ms: Double, val maxMs: Double)

    const val WINDOW = 256

    private val samples = Array(Metric.entries.size) { LongArray(WINDOW) }
    private val counts = IntArray(Metric.entries.size)

    fun record(metric: Metric, nanos: Long) {
        if (nanos < 0) return
        synchronized(samples) {
            val i = metric.ordinal
            samples[i][counts[i] % WINDOW] = nanos
            // Stays at or above WINDOW once the ring is full, without overflowing.
            counts[i] = if (counts[i] == Int.MAX_VALUE) WINDOW else counts[i] + 1
        }
    }

    inline fun <T> time(metric: Metric, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block()
        } finally {
            record(metric, System.nanoTime() - start)
        }
    }

    /** The recent samples for [metric], or null when there are none yet. */
    fun summary(metric: Metric): Summary? {
        val recent = synchronized(samples) {
            val i = metric.ordinal
            samples[i].copyOf(minOf(counts[i], WINDOW))
        }
        if (recent.isEmpty()) return null
        recent.sort()
        fun ms(nanos: Long) = nanos / 1_000_000.0
        // Nearest-rank percentile.
        fun at(fraction: Double) = recent[(Math.ceil(fraction * recent.size).toInt() - 1).coerceIn(0, recent.size - 1)]
        return Summary(recent.size, ms(at(0.5)), ms(at(0.95)), ms(recent.last()))
    }

    fun reset() {
        synchronized(samples) { counts.fill(0) }
    }
}
