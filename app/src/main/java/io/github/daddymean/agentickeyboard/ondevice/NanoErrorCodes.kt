package io.github.daddymean.agentickeyboard.ondevice

/**
 * ML Kit GenAI error codes that mean Gemini Nano is not allowed to run
 * this request, so the keyboard should try local Gemma 4 E2B instead.
 *
 * Values match [com.google.mlkit.genai.common.GenAiException.ErrorCode].
 * Kept as literals so routing tests stay pure JVM.
 */
object NanoErrorCodes {
    const val BUSY = 9
    const val BACKGROUND_USE_BLOCKED = 30

    fun isBlockedOrBusy(code: Int): Boolean =
        code == BUSY || code == BACKGROUND_USE_BLOCKED

    fun isBlockedOrBusy(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is NanoUseBlockedException) return isBlockedOrBusy(current.code)
            val code = readGenAiErrorCode(current)
            if (code != null && isBlockedOrBusy(code)) return true
            current = current.cause
        }
        return false
    }

    private fun readGenAiErrorCode(error: Throwable): Int? {
        return try {
            val method = error.javaClass.methods.firstOrNull { it.name == "getErrorCode" && it.parameterCount == 0 }
                ?: return null
            method.invoke(error) as? Int
        } catch (_: Exception) {
            null
        }
    }
}

/** Thrown (or wrapped) when Nano refuses a request because it is blocked or busy. */
class NanoUseBlockedException(
    val code: Int,
    message: String? = null,
    cause: Throwable? = null
) : RuntimeException(message ?: "Nano unavailable: $code", cause)
