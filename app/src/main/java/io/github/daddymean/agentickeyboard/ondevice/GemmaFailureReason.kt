package io.github.daddymean.agentickeyboard.ondevice

/**
 * Why local Gemma 4 E2B could not produce a rewrite. Names only — never carry
 * user text — so they are safe to log with [io.github.daddymean.agentickeyboard.util.SafeLog].
 */
enum class GemmaFailureReason {
    UNSUPPORTED_ABI,
    LOW_MEMORY,
    MODEL_MISSING,
    MODEL_CORRUPT,
    NOT_ENABLED,
    INIT_FAILED,
    INFERENCE_FAILED,
    TIMEOUT,
    EMPTY_OUTPUT,
    BUSY
}

/**
 * Typed failure from the local Gemma path so the router can fall back to
 * ML Kit (Gemini Nano) without treating the error as a cancellation.
 */
class GemmaInferenceException(
    val reason: GemmaFailureReason,
    cause: Throwable? = null
) : RuntimeException(reason.name, cause)

/**
 * Both on-device rewrite engines were attempted and failed. The message is a
 * fixed, user-visible string with no request text or exception details.
 */
class OnDeviceRewriteUnavailableException :
    RuntimeException(USER_MESSAGE) {
    companion object {
        const val USER_MESSAGE = "On-device rewrite is unavailable"
    }
}
