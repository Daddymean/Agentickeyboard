package dev.context.app.collect

import dev.context.core.model.Event

/**
 * A source of raw events, run by [CollectRunner] on the hourly pipeline.
 *
 * Implementations must be cheap and idempotent: the runner re-reads an
 * [overlapMs] window each time to catch late-arriving data, so the same
 * underlying record must always map to the same [Event.id] (use [EventIds]).
 * Emit only **closed** intervals: events are immutable once stored.
 */
interface Collector {
  /** Stable key for this collector's cursor and status, e.g. `"health"`. */
  val key: String

  /** Look-back re-read on every run for data that arrives late. */
  val overlapMs: Long
    get() = DEFAULT_OVERLAP_MS

  /**
   * What still has to be granted before [collect] can run (permission names or
   * short human-readable reasons). Empty means ready.
   */
  suspend fun missingPermissions(): List<String>

  /**
   * Called on every run before [collect], once permissions are granted.
   * Re-registers passive listeners (location, activity transitions) so they
   * survive reboots and app updates. Must be idempotent.
   */
  suspend fun ensureRegistered() {}

  /** Events observed in `[fromMs, toMs)` (a collector may look past [toMs] when its source has future data). */
  suspend fun collect(fromMs: Long, toMs: Long): List<Event>

  companion object {
    const val DEFAULT_OVERLAP_MS = 2L * 60 * 60 * 1000
  }
}
