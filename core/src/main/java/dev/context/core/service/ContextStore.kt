package dev.context.core.service

import dev.context.core.ContextContract
import dev.context.core.db.ContextDatabase
import dev.context.core.json.ContextJson
import dev.context.core.model.Event
import dev.context.core.model.EventValidator

/**
 * Service-side implementation of the event half of IContextService, kept in
 * :core so the reply budget the client relies on
 * ([ContextContract.replyMayBeTruncated]) is enforced by the same code it was
 * written against. :context-app's Stub delegates `logEvents` and `queryRange`
 * here; `getSnapshot` belongs to the distiller.
 *
 * Methods block; call them from binder threads or workers, never the main thread.
 */
class ContextStore(
  private val db: ContextDatabase,
  private val onRejected: (event: Event, reason: String) -> Unit = { _, _ -> },
) {
  /**
   * Decodes and inserts a JSON array of Event. Invalid events are skipped (and
   * reported through [onRejected]) instead of failing the batch, so one bad row
   * cannot make a client drop its neighbours. Duplicate ids are ignored.
   *
   * @return the number of rows newly inserted.
   * @throws IllegalArgumentException when [eventsJson] is not a JSON array of
   *   Event; it propagates over binder and the client treats it as non-retryable.
   */
  fun logEventsJson(eventsJson: String): Int {
    val events = ContextJson.decodeEvents(eventsJson)
    val valid = events.filter { event ->
      val reason = EventValidator.validate(event)
      if (reason != null) onRejected(event, reason)
      reason == null
    }
    if (valid.isEmpty()) return 0
    return db.events().insertAll(valid).count { it != -1L }
  }

  /**
   * Events overlapping `[fromMs, toMs)` as a JSON array ordered by
   * `(startMs, id)`, truncated before the first event that would push the reply
   * past [ContextContract.MAX_REPLY_CHARS]. Stored events larger than
   * [ContextContract.MAX_EVENT_CHARS] (only possible if written around
   * [logEventsJson]) are skipped so they cannot break the truncation signal.
   */
  fun queryRangeJson(fromMs: Long, toMs: Long, types: Array<out String>?): String {
    if (toMs <= fromMs) return "[]"
    val lookback = fromMs - ContextContract.MAX_EVENT_SPAN_MS
    val dao = db.events()
    val rows = if (types.isNullOrEmpty()) {
      dao.overlapping(fromMs, toMs, lookback, ContextContract.QUERY_ROW_LIMIT)
    } else {
      dao.overlappingOfTypes(fromMs, toMs, lookback, types.distinct(), ContextContract.QUERY_ROW_LIMIT)
    }
    val sb = StringBuilder("[")
    var count = 0
    for (event in rows) {
      val encoded = ContextJson.encodeEvent(event)
      if (encoded.length > ContextContract.MAX_EVENT_CHARS) {
        onRejected(event, "stored event exceeds MAX_EVENT_CHARS; not served over IPC")
        continue
      }
      val separator = if (count > 0) 1 else 0
      // +1 reserves room for the closing bracket.
      if (sb.length + separator + encoded.length + 1 > ContextContract.MAX_REPLY_CHARS) break
      if (separator == 1) sb.append(',')
      sb.append(encoded)
      count++
    }
    return sb.append(']').toString()
  }
}
