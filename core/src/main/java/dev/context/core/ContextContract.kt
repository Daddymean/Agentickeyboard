package dev.context.core

/**
 * Constants shared by the service (:context-app) and every client. Both sides
 * compile against this one object, so the IPC budgets cannot drift apart.
 */
object ContextContract {
  /** Signature permission guarding the service. */
  const val PERMISSION = "dev.context.permission.ACCESS"

  /** Intent action the service's <intent-filter> must declare. */
  const val ACTION_BIND = "dev.context.action.BIND_CONTEXT_SERVICE"

  /**
   * Upper bound on a queryRange reply, in chars. Parcel strings are UTF-16, so
   * this is ~256 KB on the wire — a quarter of the 1 MB per-process binder
   * buffer that all in-flight transactions share.
   */
  const val MAX_REPLY_CHARS = 128 * 1024

  /** Upper bound on one encoded Event. Larger events are rejected at ingest. */
  const val MAX_EVENT_CHARS = 16 * 1024

  /** Upper bound on one logEvents batch, in chars. */
  const val MAX_LOG_BATCH_CHARS = 128 * 1024

  /**
   * How far before `fromMs` a range query looks for events that started
   * earlier but are still running at `fromMs`. Bounds the index scan; events
   * longer than this are only found by queries whose range covers their start.
   */
  const val MAX_EVENT_SPAN_MS = 48L * 60 * 60 * 1000

  /**
   * Row cap for one queryRange page. Never the binding limit: the smallest
   * possible encoded Event is ~100 chars, so 2048 rows always exceed
   * [MAX_REPLY_CHARS] and the char budget truncates first.
   */
  const val QUERY_ROW_LIMIT = 2048

  /**
   * True when a queryRange reply of [replyChars] may have been cut short. The
   * service stops before an event that would overflow [MAX_REPLY_CHARS], and
   * every event is at most [MAX_EVENT_CHARS], so a truncated reply is always
   * longer than this threshold. False positives only cost one extra page.
   */
  fun replyMayBeTruncated(replyChars: Int): Boolean =
    replyChars > MAX_REPLY_CHARS - MAX_EVENT_CHARS - 1
}
