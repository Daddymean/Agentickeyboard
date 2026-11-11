package io.github.daddymean.agentickeyboard.ondevice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class GemmaGenerateGuardTest {

    @Test
    fun returnsSuccessfulOutput() = runTest {
        assertEquals("rewritten", GemmaGenerateGuard.run { "rewritten" })
    }

    @Test
    fun emptyOutputIsATypedFailure() = runTest {
        try {
            GemmaGenerateGuard.run { null }
            fail("expected empty output to fail")
        } catch (e: GemmaInferenceException) {
            assertEquals(GemmaFailureReason.EMPTY_OUTPUT, e.reason)
        }
    }

    @Test
    fun timeoutDoesNotPropagateAsCancellation() = runTest {
        try {
            GemmaGenerateGuard.run(timeoutMs = 1_000) {
                delay(5_000)
                "late"
            }
            fail("expected timeout")
        } catch (e: CancellationException) {
            fail("timeout must not look like a coroutine cancellation: $e")
        } catch (e: GemmaInferenceException) {
            assertEquals(GemmaFailureReason.TIMEOUT, e.reason)
        }
    }

    @Test
    fun initExceptionIsClassified() = runTest {
        try {
            GemmaGenerateGuard.run {
                throw IllegalStateException("LiteRT-LM Engine failed to initialize on GPU and CPU")
            }
            fail("expected init failure")
        } catch (e: GemmaInferenceException) {
            assertEquals(GemmaFailureReason.INIT_FAILED, e.reason)
        }
    }

    @Test
    fun inferenceExceptionIsClassified() = runTest {
        try {
            GemmaGenerateGuard.run { error("native crash") }
            fail("expected inference failure")
        } catch (e: GemmaInferenceException) {
            assertEquals(GemmaFailureReason.INFERENCE_FAILED, e.reason)
        }
    }

    @Test
    fun realCancellationIsRethrown() = runTest {
        val deferred = async {
            GemmaGenerateGuard.run {
                delay(10_000)
                "done"
            }
        }
        deferred.cancel()
        try {
            deferred.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
            // Expected: a real cancel must not be turned into GemmaInferenceException.
        }
    }
}
