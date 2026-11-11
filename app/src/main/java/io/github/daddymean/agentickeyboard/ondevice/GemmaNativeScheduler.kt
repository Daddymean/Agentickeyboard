package io.github.daddymean.agentickeyboard.ondevice

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/**
 * Runs blocking LiteRT-LM work (initialize / sendMessage via reflection, no
 * cancellation points) on a single worker thread, and races the caller against
 * a timeout.
 *
 * When the deadline passes, the caller returns immediately so the router can
 * fall back to ML Kit. The worker is left running; its late result is
 * discarded and never returned. [isBusy] stays true until that call finishes,
 * so a later request skips Gemma instead of queueing a second native call.
 * There is at most one worker thread and at most one in-flight call.
 *
 * Pure JVM so tests can use a fake that blocks forever.
 */
class GemmaNativeScheduler(
    executor: ExecutorService? = null
) {
    private val ownsExecutor = executor == null
    private val executor: ExecutorService = executor ?: Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "gemma-litert").apply { isDaemon = true }
    }
    private val inFlight = AtomicBoolean(false)

    /** True while a native call is running, including after the waiter has timed out. */
    val isBusy: Boolean get() = inFlight.get()

    suspend fun run(
        timeoutMs: Long = GemmaGenerateGuard.DEFAULT_TIMEOUT_MS,
        onDiscarded: () -> Unit = {},
        block: () -> String?
    ): String {
        if (!inFlight.compareAndSet(false, true)) {
            throw GemmaInferenceException(GemmaFailureReason.BUSY)
        }
        val future = CompletableFuture<Result<String>>()
        val timedOut = AtomicBoolean(false)
        try {
            executor.execute {
                try {
                    future.complete(Result.success(GemmaGenerateGuard.consume(block)))
                } catch (t: Throwable) {
                    future.complete(Result.failure(GemmaGenerateGuard.wrap(t)))
                } finally {
                    if (timedOut.get()) {
                        try {
                            onDiscarded()
                        } catch (_: Exception) {
                        }
                    }
                    inFlight.set(false)
                }
            }
        } catch (t: Throwable) {
            inFlight.set(false)
            throw GemmaGenerateGuard.wrap(t)
        }
        return try {
            withTimeout(timeoutMs) { awaitWithoutCancelling(future, timedOut) }.getOrThrow()
        } catch (e: TimeoutCancellationException) {
            timedOut.set(true)
            throw GemmaInferenceException(GemmaFailureReason.TIMEOUT)
        }
    }

    fun shutdown() {
        if (ownsExecutor) executor.shutdown()
    }

    /**
     * Waits for [future] without cancelling it. A timeout or coroutine cancel
     * must not interrupt the worker: native LiteRT-LM calls ignore interrupts,
     * and a late result must simply be dropped.
     */
    private suspend fun awaitWithoutCancelling(
        future: CompletableFuture<Result<String>>,
        timedOut: AtomicBoolean
    ): Result<String> = suspendCancellableCoroutine { cont ->
        future.whenComplete { value, error ->
            if (!cont.isActive) return@whenComplete
            when {
                error != null -> cont.resumeWithException(GemmaGenerateGuard.wrap(error))
                value != null -> cont.resume(value)
                else -> cont.resumeWithException(
                    GemmaInferenceException(GemmaFailureReason.EMPTY_OUTPUT)
                )
            }
        }
        cont.invokeOnCancellation { timedOut.set(true) }
    }
}
