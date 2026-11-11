package io.github.daddymean.agentickeyboard.ondevice

/**
 * Pure-JVM checks for whether this device and model file may run Gemma 4 E2B.
 * The ~2.59 GB weights plus runtime need enough RAM that attempting inference
 * on a small phone can OOM the IME process; better to skip and use ML Kit.
 */
object GemmaEligibility {

    /** Floor on [android.app.ActivityManager.MemoryInfo.totalMem]. */
    const val MIN_TOTAL_RAM_BYTES: Long = 6L * 1024L * 1024L * 1024L

    val SUPPORTED_ABIS: Set<String> = setOf("arm64-v8a", "x86_64")

    fun deviceSupported(abis: List<String>, totalRamBytes: Long): Boolean =
        abiSupported(abis) && totalRamBytes >= MIN_TOTAL_RAM_BYTES

    fun abiSupported(abis: List<String>): Boolean =
        abis.any { it in SUPPORTED_ABIS }

    fun decide(
        enabled: Boolean,
        modelPresent: Boolean,
        modelVerified: Boolean,
        abis: List<String>,
        totalRamBytes: Long
    ): GemmaFailureReason? {
        if (!enabled) return GemmaFailureReason.NOT_ENABLED
        if (!abiSupported(abis)) return GemmaFailureReason.UNSUPPORTED_ABI
        if (totalRamBytes < MIN_TOTAL_RAM_BYTES) return GemmaFailureReason.LOW_MEMORY
        if (!modelPresent) return GemmaFailureReason.MODEL_MISSING
        if (!modelVerified) return GemmaFailureReason.MODEL_CORRUPT
        return null
    }
}
