package dev.context.core.client

import dev.context.core.ContextContract
import dev.context.core.json.ContextJson
import dev.context.core.testing.testEvent
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EventBatcherTest {
  /** Records every delivered batch as its list of event ids. */
  private class FakeService {
    val batches = mutableListOf<List<String>>()
    var attempts = 0
    var failures: (attempt: Int) -> Throwable? = { null }

    suspend fun send(json: String) {
      attempts++
      failures(attempts)?.let { throw it }
      batches += ContextJson.decodeEvents(json).map { it.id }
    }

    val delivered: List<String> get() = batches.flatten()
  }

  private class RecordingListener : BatcherListener {
    val dropped = mutableListOf<Pair<Int, DropReason>>()
    var spooled = 0

    override fun onDropped(count: Int, reason: DropReason, cause: Throwable?) {
      dropped += count to reason
    }

    override fun onSpooled(count: Int, cause: Throwable) {
      spooled += count
    }
  }

  private val noJitter = BatchConfig(maxDelayMs = 5_000, jitterRatio = 0.0)

  private fun TestScope.batcher(
    service: FakeService,
    config: BatchConfig = noJitter,
    spool: EventSpool? = null,
    listener: BatcherListener = BatcherListener.NONE,
  ) = EventBatcher(
    scope = backgroundScope,
    config = config,
    spool = spool,
    listener = listener,
    clock = { testScheduler.currentTime },
    send = service::send,
  )

  @Test
  fun waitsForMaxDelayThenSendsOneBatch() = runTest {
    val service = FakeService()
    val batcher = batcher(service)
    batcher.enqueue(listOf(testEvent(1), testEvent(2)))
    batcher.enqueue(listOf(testEvent(3)))
    runCurrent()
    advanceTimeBy(4_000)
    runCurrent()
    assertTrue(service.batches.isEmpty())

    advanceTimeBy(1_001)
    runCurrent()
    assertEquals(listOf(listOf("evt-00001", "evt-00002", "evt-00003")), service.batches)
    assertEquals(0, batcher.queuedCount)
  }

  @Test
  fun urgentEnqueueSendsWithoutWaiting() = runTest {
    val service = FakeService()
    val batcher = batcher(service)
    batcher.enqueue(listOf(testEvent(1)), urgent = true)
    runCurrent()
    assertEquals(listOf("evt-00001"), service.delivered)
  }

  @Test
  fun splitsByMaxBatchEvents() = runTest {
    val service = FakeService()
    val batcher = batcher(service, noJitter.copy(maxBatchEvents = 2))
    batcher.enqueue((1..5).map { testEvent(it) })
    assertTrue(batcher.flush())
    assertEquals(listOf(2, 2, 1), service.batches.map { it.size })
    assertEquals((1..5).map { "evt-%05d".format(it) }, service.delivered)
  }

  @Test
  fun splitsByCharBudget() = runTest {
    val service = FakeService()
    val batcher = batcher(service, noJitter.copy(maxBatchChars = ContextContract.MAX_EVENT_CHARS + 2))
    val bulky = """{"t":"${"x".repeat(7_000)}"}"""
    batcher.enqueue((1..5).map { testEvent(it, payload = bulky) })
    assertTrue(batcher.flush())
    assertEquals(listOf(2, 2, 1), service.batches.map { it.size })
  }

  @Test
  fun retriesWithBackoffAndKeepsOrder() = runTest {
    val service = FakeService().apply { failures = { if (it <= 2) IOException("down") else null } }
    val batcher = batcher(service)
    batcher.enqueue((1..3).map { testEvent(it) })
    val start = testScheduler.currentTime
    assertTrue(batcher.flush())
    assertEquals(3, service.attempts)
    assertEquals(listOf("evt-00001", "evt-00002", "evt-00003"), service.delivered)
    // 1 s + 2 s of backoff with jitter disabled.
    assertEquals(3_000, testScheduler.currentTime - start)
  }

  @Test
  fun rejectedBatchIsDroppedWithoutRetry() = runTest {
    val service = FakeService().apply { failures = { if (it == 1) IllegalArgumentException("bad json") else null } }
    val listener = RecordingListener()
    val batcher = batcher(service, listener = listener)
    batcher.enqueue(listOf(testEvent(1)))
    batcher.flush()
    assertEquals(1, service.attempts)
    assertEquals(listOf(1 to DropReason.REJECTED_BY_SERVICE), listener.dropped)
  }

  @Test
  fun exhaustedRetriesSpoolThenSpoolDrainsAfterRecovery() = runTest {
    var down = true
    val service = FakeService().apply { failures = { if (down) IOException("down") else null } }
    val spool = InMemoryEventSpool()
    val listener = RecordingListener()
    val batcher = batcher(service, noJitter.copy(maxAttempts = 2), spool, listener)

    batcher.enqueue((1..3).map { testEvent(it) })
    assertFalse(batcher.flush())
    assertEquals(3, spool.snapshot().size)
    assertEquals(3, listener.spooled)
    assertEquals(0, batcher.queuedCount)

    down = false
    batcher.enqueue(listOf(testEvent(4)))
    assertTrue(batcher.flush())
    assertTrue(spool.isEmpty())
    assertEquals(setOf("evt-00001", "evt-00002", "evt-00003", "evt-00004"), service.delivered.toSet())
  }

  @Test
  fun securityExceptionIsNotRetried() = runTest {
    val service = FakeService().apply { failures = { SecurityException("no permission") } }
    val spool = InMemoryEventSpool()
    val batcher = batcher(service, spool = spool)
    batcher.enqueue(listOf(testEvent(1)))
    assertFalse(batcher.flush())
    assertEquals(1, service.attempts)
    assertEquals(1, spool.snapshot().size)
  }

  @Test
  fun withoutSpoolUndeliverableEventsAreReportedDropped() = runTest {
    val service = FakeService().apply { failures = { IOException("down") } }
    val listener = RecordingListener()
    val batcher = batcher(service, noJitter.copy(maxAttempts = 1), listener = listener)
    batcher.enqueue(listOf(testEvent(1), testEvent(2)))
    assertFalse(batcher.flush())
    assertEquals(listOf(2 to DropReason.UNDELIVERABLE), listener.dropped)
  }

  @Test
  fun invalidEventsAreRejectedAtEnqueue() = runTest {
    val service = FakeService()
    val listener = RecordingListener()
    val batcher = batcher(service, listener = listener)
    val accepted = batcher.enqueue(listOf(testEvent(1), testEvent(2).copy(sensitivity = 9)))
    assertEquals(1, accepted)
    assertEquals(listOf(1 to DropReason.INVALID), listener.dropped)
    batcher.flush()
    assertEquals(listOf("evt-00001"), service.delivered)
  }

  @Test
  fun overflowDropsOldest() = runTest {
    val service = FakeService()
    val listener = RecordingListener()
    val batcher = batcher(service, noJitter.copy(maxQueuedEvents = 3), listener = listener)
    batcher.enqueue((1..5).map { testEvent(it) })
    assertEquals(listOf(2 to DropReason.QUEUE_OVERFLOW), listener.dropped)
    batcher.flush()
    assertEquals(listOf("evt-00003", "evt-00004", "evt-00005"), service.delivered)
  }

  @Test
  fun closeSpoolsWhatCouldNotBeSentAndRejectsLaterEnqueues() = runTest {
    val service = FakeService().apply { failures = { IOException("down") } }
    val spool = InMemoryEventSpool()
    val listener = RecordingListener()
    val batcher = batcher(service, noJitter.copy(maxAttempts = 10), spool, listener)
    batcher.enqueue(listOf(testEvent(1), testEvent(2)))
    batcher.close(timeoutMs = 500)
    assertEquals(2, spool.snapshot().size)
    assertEquals(0, batcher.enqueue(listOf(testEvent(3))))
    assertTrue(listener.dropped.contains(1 to DropReason.CLOSED))
  }

  @Test
  fun backoffGrowsAndCaps() = runTest {
    val batcher = batcher(FakeService(), noJitter.copy(initialBackoffMs = 1_000, maxBackoffMs = 5_000))
    assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L, 5_000L), (1..5).map(batcher::backoffMs))
  }
}
