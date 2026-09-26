package dev.context.core.client

import android.content.Context
import android.os.SystemClock
import dev.context.core.json.ContextJson
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import dev.context.core.model.Snapshot
import java.io.Closeable
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client SDK for IContextService, used by the keyboard and collectors.
 *
 * - [cachedSnapshot] / [snapshot] never touch binder: safe on the keystroke path.
 * - [logEvents] only enqueues; batching, retry and on-disk spooling happen on a
 *   background coroutine (see [EventBatcher]).
 * - The binding is created on first use and dropped after
 *   [Config.idleUnbindMs] idle, so the service process is not pinned.
 *
 * Create one per process and [close] it when the host component is destroyed.
 */
class ContextClient(
  context: Context,
  private val config: Config = Config(),
  private val listener: BatcherListener = BatcherListener.NONE,
) : Closeable {
  data class Config(
    /** Restrict resolution to this package; null resolves by action + signature. */
    val servicePackage: String? = null,
    val bindTimeoutMs: Long = 5_000,
    val idleUnbindMs: Long = 30_000,
    /** A cached snapshot younger than this is served without a binder call. */
    val snapshotMaxAgeMs: Long = 60_000,
    /** After a failed snapshot fetch, don't try again sooner than this. */
    val snapshotRetryAfterFailureMs: Long = 15_000,
    val batch: BatchConfig = BatchConfig(),
    /** File name in noBackupFilesDir for undeliverable events; null disables. */
    val spoolFileName: String? = "dev.context.client.spool.jsonl",
    val spoolMaxBytes: Long = 4L * 1024 * 1024,
  )

  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ContextClient"))
  private val connector = ServiceConnector(
    appContext, scope, config.servicePackage, config.bindTimeoutMs, config.idleUnbindMs,
  )
  private val batcher = EventBatcher(
    scope = scope,
    config = config.batch,
    spool = config.spoolFileName?.let { FileEventSpool(File(appContext.noBackupFilesDir, it), config.spoolMaxBytes) },
    listener = listener,
    send = { json -> connector.call { it.logEvents(json) } },
  )
  private val pager = RangeQueryPager { from, to, types -> connector.call { it.queryRange(from, to, types) } }

  private val snapshotState = MutableStateFlow<Snapshot?>(null)
  private val snapshotLock = Any()
  private var snapshotFetchedAt = Long.MIN_VALUE
  private var snapshotFailedAt = Long.MIN_VALUE
  private var snapshotInFlight: Deferred<Snapshot?>? = null

  /** Latest snapshot this client has seen; null until the first fetch succeeds. */
  val snapshot: StateFlow<Snapshot?> = snapshotState.asStateFlow()

  /** Non-blocking read of the cache; kicks off a background refresh when stale. */
  fun cachedSnapshot(): Snapshot? {
    if (isStale(config.snapshotMaxAgeMs)) refreshSnapshot()
    return snapshotState.value
  }

  /** Starts a background fetch unless one is running or a recent one failed. */
  fun refreshSnapshot() {
    startFetchIfAllowed()
  }

  /**
   * Returns a snapshot no older than [maxAgeMs], fetching if needed. On failure
   * returns the stale cached value (or null if there never was one) rather than
   * throwing: callers are UI and should degrade, not crash.
   */
  suspend fun getSnapshot(maxAgeMs: Long = config.snapshotMaxAgeMs): Snapshot? {
    if (!isStale(maxAgeMs)) return snapshotState.value
    val fetch = startFetchIfAllowed() ?: return snapshotState.value
    return fetch.await()
  }

  /** Queues events for delivery. Never blocks; invalid events are dropped. */
  fun logEvents(events: List<Event>, urgent: Boolean = false): Int = batcher.enqueue(events, urgent)

  fun logEvent(event: Event, urgent: Boolean = false): Int = batcher.enqueue(listOf(event), urgent)

  /**
   * Convenience for the keyboard: builds and urgently queues a `kb.note` with
   * payload `{"text": ...}`. [sensitivity] is deliberately required — whether
   * a note may sync off-device is the caller's decision, not a default.
   */
  fun logNote(text: String, sensitivity: Int, source: String = appContext.packageName): Event {
    val now = System.currentTimeMillis()
    val event = Event(
      id = UUID.randomUUID().toString(),
      type = EventTypes.KB_NOTE,
      startMs = now,
      endMs = null,
      source = source,
      payload = buildJsonObject { put("text", text) }.toString(),
      sensitivity = sensitivity,
      createdMs = now,
    )
    batcher.enqueue(listOf(event), urgent = true)
    return event
  }

  /** Sends everything queued now. True when all of it (and the spool) was delivered. */
  suspend fun flush(): Boolean = batcher.flush()

  /**
   * Events overlapping `[fromMs, toMs)`, ordered by `(startMs, id)`; empty
   * [types] means all types. Transparently pages past the per-reply budget.
   *
   * @throws ServiceUnavailableException when the service cannot be reached.
   */
  suspend fun queryRange(fromMs: Long, toMs: Long, types: Collection<String> = emptyList()): List<Event> =
    pager.fetch(fromMs, toMs, types.toTypedArray())

  /**
   * Flushes (bounded to ~2 s), spools the remainder, and unbinds, all in the
   * background: safe to call from `onDestroy` on the main thread.
   */
  override fun close() {
    scope.launch {
      withContext(NonCancellable) { batcher.close() }
      connector.close()
      scope.cancel()
    }
  }

  private fun isStale(maxAgeMs: Long): Boolean {
    val fetchedAt = synchronized(snapshotLock) { snapshotFetchedAt }
    return fetchedAt == Long.MIN_VALUE || SystemClock.elapsedRealtime() - fetchedAt > maxAgeMs
  }

  private fun startFetchIfAllowed(): Deferred<Snapshot?>? = synchronized(snapshotLock) {
    snapshotInFlight?.let { return it }
    val now = SystemClock.elapsedRealtime()
    if (snapshotFailedAt != Long.MIN_VALUE && now - snapshotFailedAt < config.snapshotRetryAfterFailureMs) {
      return null
    }
    scope.async {
      val fresh = try {
        ContextJson.decodeSnapshot(connector.call { it.snapshot })
      } catch (e: Exception) {
        null
      }
      synchronized(snapshotLock) {
        snapshotInFlight = null
        if (fresh != null) {
          snapshotFetchedAt = SystemClock.elapsedRealtime()
          snapshotFailedAt = Long.MIN_VALUE
        } else {
          snapshotFailedAt = SystemClock.elapsedRealtime()
        }
      }
      if (fresh != null) snapshotState.value = fresh
      fresh ?: snapshotState.value
    }.also { snapshotInFlight = it }
  }
}
