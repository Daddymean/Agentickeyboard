package io.github.daddymean.agentickeyboard.ondevice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GemmaNativeSchedulerTest {

    private val scheduler = GemmaNativeScheduler()

    @After
    fun tearDown() {
        scheduler.shutdown()
    }

    @Test
    fun returnsSuccessfulOutput() = runBlocking {
        assertEquals("rewritten", scheduler.run { "rewritten" })
        assertFalse(scheduler.isBusy)
    }

    @Test
    fun blockingHangTimesOutWithoutWaitingForNative() = runBlocking {
        val hang = CountDownLatch(1)
        val started = System.nanoTime()
        try {
            try {
                scheduler.run(timeoutMs = 200) {
                    hang.await()
                    "late"
                }
                fail("expected timeout")
            } catch (e: GemmaInferenceException) {
                assertEquals(GemmaFailureReason.TIMEOUT, e.reason)
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("fallback should happen near the 200ms timeout, was ${elapsedMs}ms", elapsedMs < 1_500)
            assertTrue(scheduler.isBusy)
        } finally {
            hang.countDown()
            waitUntilIdle()
        }
    }

    @Test
    fun lateResultIsDiscardedAndNeverReturned() = runBlocking {
        val hang = CountDownLatch(1)
        val late = AtomicReference<String?>(null)
        val discarded = AtomicInteger(0)
        try {
            try {
                val result = scheduler.run(
                    timeoutMs = 200,
                    onDiscarded = { discarded.incrementAndGet() }
                ) {
                    hang.await()
                    late.set("should-discard")
                    "should-discard"
                }
                fail("expected timeout, got $result")
            } catch (e: GemmaInferenceException) {
                assertEquals(GemmaFailureReason.TIMEOUT, e.reason)
            }
            assertNull(late.get())
        } finally {
            hang.countDown()
            waitUntilIdle()
        }
        assertEquals("should-discard", late.get())
        assertEquals(1, discarded.get())
        assertEquals("next", scheduler.run { "next" })
    }

    @Test
    fun secondCallWhileHungIsBusyImmediately() = runBlocking {
        val hang = CountDownLatch(1)
        val firstStarted = CountDownLatch(1)
        try {
            val first = async(Dispatchers.IO) {
                scheduler.run(timeoutMs = 2_000) {
                    firstStarted.countDown()
                    hang.await()
                    "late"
                }
            }
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
            val started = System.nanoTime()
            try {
                scheduler.run(timeoutMs = 2_000) { "should-not-run" }
                fail("expected busy")
            } catch (e: GemmaInferenceException) {
                assertEquals(GemmaFailureReason.BUSY, e.reason)
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("busy should be immediate, was ${elapsedMs}ms", elapsedMs < 500)
            first.cancel()
        } finally {
            hang.countDown()
            waitUntilIdle()
        }
    }

    private fun waitUntilIdle() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (scheduler.isBusy && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        assertFalse("worker should have finished after the hang was released", scheduler.isBusy)
    }
}
