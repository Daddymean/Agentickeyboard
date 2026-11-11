package io.github.daddymean.agentickeyboard.ondevice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Boundary around local Gemma init/inference: a timeout, typed failures, and
 * no rethrow of [TimeoutCancellationException] (that would abort the rewrite
 * and skip the ML Kit fallback).
 *
 * Pure JVM so unit tests can drive it with virtual time and fakes.
 */
object GemmaGenerateGuard {

    /** Short keyboard rewrite; long enough for E2B, short enough to fall back. */
    const val DEFAULT_TIMEOUT_MS: Long = 20_000L

    suspend fun run(
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        block: suspend () -> String?
    ): String {
        try {
            return withTimeout(timeoutMs) {
                block() ?: throw GemmaInferenceException(GemmaFailureReason.EMPTY_OUTPUT)
            }
        } catch (e: TimeoutCancellationException) {
            throw GemmaInferenceException(GemmaFailureReason.TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GemmaInferenceException) {
            throw e
        } catch (t: Throwable) {
            throw wrap(t)
        }
    }

    /** Blocking equivalent used by [GemmaNativeScheduler] on the worker thread. */
    fun consume(block: () -> String?): String {
        return try {
            block() ?: throw GemmaInferenceException(GemmaFailureReason.EMPTY_OUTPUT)
        } catch (e: GemmaInferenceException) {
            throw e
        } catch (t: Throwable) {
            throw wrap(t)
        }
    }

    fun wrap(error: Throwable): GemmaInferenceException {
        var current: Throwable? = error
        while (current != null) {
            if (current is GemmaInferenceException) return current
            current = current.cause
        }
        val reason = if (error is IllegalStateException && isInitFailure(error)) {
            GemmaFailureReason.INIT_FAILED
        } else {
            GemmaFailureReason.INFERENCE_FAILED
        }
        return GemmaInferenceException(reason, error)
    }

    private fun isInitFailure(error: Throwable): Boolean {
        val message = error.message ?: return false
        return message.contains("initialize", ignoreCase = true)
    }
}
