package dev.context.core.client

import dev.context.core.ContextContract
import dev.context.core.json.ContextJson
import dev.context.core.model.Event
import dev.context.core.model.EventValidator
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.pow
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Tuning for [EventBatcher]. Defaults favour battery over latency. */
data class BatchConfig(
  /** How long the oldest queued event may wait before a send is forced. */
  val maxDelayMs: Long = 10_000,
  val maxBatchEvents: Int = 500,
  val maxBatchChars: Int = ContextContract.MAX_LOG_BATCH_CHARS,
  /** In-memory cap; beyond it the oldest events are dropped. */
  val maxQueuedEvents: Int = 5_000,
  /** Total attempts per batch, including the first. */
  val maxAttempts: Int = 5,
  val initialBackoffMs: Long = 1_000,
  val maxBackoffMs: Long = 60_000,
  val backoffMultiplier: Double = 2.0,
  /** Backoff is randomised by ±this fraction to avoid lock-step retries. */
  val jitterRatio: Double = 0.2,
) {
  init {
    require(maxDelayMs >= 0 && maxBatchEvents > 0 && maxQueuedEvents > 0 && maxAttempts > 0)
    require(maxBatchChars >= ContextContract.MAX_EVENT_CHARS + 2) { "a batch must fit one max-size event" }
    require(jitterRatio in 0.0..1.0)
  }
}

enum class DropReason {
  /** Failed [EventValidator]; never queued. */
  INVALID,
  /** Queue exceeded [BatchConfig.maxQueuedEvents]. */
  QUEUE_OVERFLOW,
  /** Service answered IllegalArgumentException — retrying cannot help. */
  REJECTED_BY_SERVICE,
  /** Retries exhausted and there is no spool (or it failed). */
  UNDELIVERABLE,
  /** Spool is at its size cap. */
  SPOOL_FULL,
  /** Enqueued after [EventBatcher.close]. */
  CLOSED,
}

/** Observability hook; called on the batcher's worker or the enqueuing thread. */
interface BatcherListener {
  fun onSent(count: Int) {}

  fun onSpooled(count: Int, cause: Throwable) {}

  fun onDropped(count: Int, reason: DropReason, cause: Throwable?) {}

  companion object {
    val NONE = object : BatcherListener {}
  }
}

/**
 * Coalesces events into few, bounded `logEvents` calls. [enqueue] never blocks
 * or does I/O, so it is safe on the IME main thread; one worker coroutine does
 * all sending, in order, with exponential backoff.
 *
 * Delivery is at-least-once: a batch whose reply is lost is re-sent, which is
 * harmless because the service ignores duplicate ids. Batches that exhaust
 * their retries (and everything queued behind them) go to [spool] and are
 * re-sent after the next successful delivery.
 */
class EventBatcher(
  scope: CoroutineScope,
  private val config: BatchConfig = BatchConfig(),
  private val spool: EventSpool? = null,
  private val listener: BatcherListener = BatcherListener.NONE,
  private val clock: () -> Long = System::currentTimeMillis,
  private val random: Random = Random.Default,
  /** Delivers one JSON array of Event; normally `IContextService.logEvents`. */
  private val send: suspend (eventsJson: String) -> Unit,
) {
  private class Pending(val json: String, val enqueuedAt: Long)

  private class Batch(val json: String, val size: Int)

  private sealed interface Outcome {
    object Sent : Outcome
    class Rejected(val cause: Throwable) : Outcome
    class Failed(val cause: Throwable) : Outcome
  }

  private val lock = Any()
  private val queue = ArrayDeque<Pending>()
  private var queuedChars = 0
  private var urgent = false
  private var closed = false
  private val flushWaiters = ArrayList<CompletableDeferred<Boolean>>()
  private val wake = Channel<Unit>(Channel.CONFLATED)
  private val worker: Job = scope.launch { runWorker() }

  /** Number of events waiting in memory (not counting the spool). */
  val queuedCount: Int
    get() = synchronized(lock) { queue.size }

  /**
   * Validates and queues [events]. With [urgent] the worker sends immediately
   * instead of waiting for [BatchConfig.maxDelayMs] (use for user-authored
   * notes). Returns how many events were accepted.
   */
  fun enqueue(events: List<Event>, urgent: Boolean = false): Int {
    if (events.isEmpty()) return 0
    val now = clock()
    val accepted = ArrayList<Pending>(events.size)
    var invalid = 0
    for (event in events) {
      val json = ContextJson.encodeEvent(event)
      if (EventValidator.validate(event, json) == null) accepted += Pending(json, now) else invalid++
    }
    if (invalid > 0) listener.onDropped(invalid, DropReason.INVALID, null)
    if (accepted.isEmpty()) return 0

    var overflow = 0
    synchronized(lock) {
      if (closed) {
        listener.onDropped(accepted.size, DropReason.CLOSED, null)
        return 0
      }
      for (p in accepted) {
        queue.addLast(p)
        queuedChars += p.json.length
      }
      while (queue.size > config.maxQueuedEvents) {
        queuedChars -= queue.removeFirst().json.length
        overflow++
      }
      if (urgent) this.urgent = true
    }
    if (overflow > 0) listener.onDropped(overflow, DropReason.QUEUE_OVERFLOW, null)
    wake.trySend(Unit)
    return accepted.size
  }

  /**
   * Sends everything queued now (and the spool backlog). Returns true when all
   * of it was delivered, false when some of it was spooled or dropped instead.
   */
  suspend fun flush(): Boolean {
    val done = CompletableDeferred<Boolean>()
    synchronized(lock) {
      if (closed) return queue.isEmpty()
      flushWaiters += done
    }
    wake.trySend(Unit)
    return done.await()
  }

  /**
   * Stops the worker after a best-effort flush bounded by [timeoutMs]; whatever
   * is still queued is spooled (or reported dropped). Idempotent.
   */
  suspend fun close(timeoutMs: Long = 2_000) {
    if (synchronized(lock) { closed }) return
    withTimeoutOrNull(timeoutMs) { flush() }
    val rest: List<Pending>
    val waiters: List<CompletableDeferred<Boolean>>
    // Cancel first: an in-flight send may still land, and the spooled copy is
    // then a harmless duplicate the service ignores by id.
    worker.cancel()
    worker.join()
    synchronized(lock) {
      closed = true
      rest = queue.toList()
      queue.clear()
      queuedChars = 0
      waiters = flushWaiters.toList()
      flushWaiters.clear()
    }
    if (rest.isNotEmpty()) spoolOrDrop(rest.map { it.json }, CancellationException("batcher closed"))
    waiters.forEach { it.complete(false) }
  }

  private suspend fun runWorker() {
    while (true) {
      wake.receive()
      // Debounce: wait for the batch to fill or the oldest event to age out.
      while (true) {
        val waitMs = synchronized(lock) { waitBeforeSendLocked() }
        if (waitMs <= 0) break
        withTimeoutOrNull(waitMs) { wake.receive() }
      }
      val ok = drain()
      val waiters = synchronized(lock) { flushWaiters.toList().also { flushWaiters.clear() } }
      waiters.forEach { it.complete(ok) }
    }
  }

  private fun waitBeforeSendLocked(): Long = when {
    urgent || flushWaiters.isNotEmpty() -> 0L
    queue.isEmpty() -> 0L
    queue.size >= config.maxBatchEvents -> 0L
    ContextJson.arrayLength(queuedChars, queue.size) >= config.maxBatchChars -> 0L
    else -> queue.first().enqueuedAt + config.maxDelayMs - clock()
  }

  /** Sends the queue, then the spool backlog. Returns false on any undelivered event. */
  private suspend fun drain(): Boolean {
    while (true) {
      val batch = synchronized(lock) {
        urgent = false
        nextBatchLocked()
      } ?: break
      when (val outcome = sendWithRetry(batch.json)) {
        Outcome.Sent -> {
          synchronized(lock) { removeHeadLocked(batch.size) }
          listener.onSent(batch.size)
        }
        is Outcome.Rejected -> {
          synchronized(lock) { removeHeadLocked(batch.size) }
          listener.onDropped(batch.size, DropReason.REJECTED_BY_SERVICE, outcome.cause)
        }
        is Outcome.Failed -> {
          // Service is unreachable: park the whole queue on disk rather than
          // spending another retry cycle per batch.
          val rest = synchronized(lock) {
            queue.map { it.json }.also {
              queue.clear()
              queuedChars = 0
            }
          }
          spoolOrDrop(rest, outcome.cause)
          return false
        }
      }
    }
    return drainSpool()
  }

  private suspend fun drainSpool(): Boolean {
    val spool = spool ?: return true
    while (true) {
      val batch = try {
        if (spool.isEmpty()) return true
        spool.peek(config.maxBatchChars, config.maxBatchEvents)
      } catch (e: IOException) {
        return false
      }
      if (batch.consumed == 0) return true
      val outcome = if (batch.events.isEmpty()) Outcome.Sent else sendWithRetry(ContextJson.joinArray(batch.events))
      when (outcome) {
        Outcome.Sent -> if (batch.events.isNotEmpty()) listener.onSent(batch.events.size)
        is Outcome.Rejected -> listener.onDropped(batch.events.size, DropReason.REJECTED_BY_SERVICE, outcome.cause)
        is Outcome.Failed -> return false
      }
      try {
        spool.remove(batch.consumed)
      } catch (e: IOException) {
        return false
      }
    }
  }

  private fun nextBatchLocked(): Batch? {
    if (queue.isEmpty()) return null
    val items = ArrayList<String>()
    var chars = 0
    for (p in queue) {
      if (items.size >= config.maxBatchEvents) break
      if (items.isNotEmpty() &&
        ContextJson.arrayLength(chars + p.json.length, items.size + 1) > config.maxBatchChars
      ) break
      items += p.json
      chars += p.json.length
    }
    return Batch(ContextJson.joinArray(items), items.size)
  }

  private fun removeHeadLocked(count: Int) {
    repeat(minOf(count, queue.size)) { queuedChars -= queue.removeFirst().json.length }
  }

  private suspend fun sendWithRetry(json: String): Outcome {
    var attempt = 0
    while (true) {
      try {
        send(json)
        return Outcome.Sent
      } catch (e: CancellationException) {
        // Our own cancellation propagates; a stray timeout from below is a failure.
        currentCoroutineContext().ensureActive()
        attempt++
        if (attempt >= config.maxAttempts) return Outcome.Failed(e)
      } catch (e: IllegalArgumentException) {
        return Outcome.Rejected(e)
      } catch (e: SecurityException) {
        // Missing permission or untrusted service: retrying cannot fix it.
        return Outcome.Failed(e)
      } catch (e: Exception) {
        attempt++
        if (attempt >= config.maxAttempts) return Outcome.Failed(e)
      }
      delay(backoffMs(attempt))
    }
  }

  internal fun backoffMs(attempt: Int): Long {
    val base = (config.initialBackoffMs * config.backoffMultiplier.pow(attempt - 1))
      .coerceAtMost(config.maxBackoffMs.toDouble())
    val jitter = if (config.jitterRatio == 0.0) 0.0 else base * config.jitterRatio * (random.nextDouble() * 2 - 1)
    return (base + jitter).toLong().coerceAtLeast(0)
  }

  private fun spoolOrDrop(items: List<String>, cause: Throwable) {
    if (items.isEmpty()) return
    val spool = spool
    if (spool == null) {
      listener.onDropped(items.size, DropReason.UNDELIVERABLE, cause)
      return
    }
    val accepted = try {
      spool.append(items)
    } catch (e: IOException) {
      listener.onDropped(items.size, DropReason.UNDELIVERABLE, e)
      return
    }
    if (accepted > 0) listener.onSpooled(accepted, cause)
    if (accepted < items.size) listener.onDropped(items.size - accepted, DropReason.SPOOL_FULL, cause)
  }
}
