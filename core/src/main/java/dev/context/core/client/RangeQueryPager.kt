package dev.context.core.client

import dev.context.core.ContextContract
import dev.context.core.json.ContextJson
import dev.context.core.model.Event

/**
 * Reassembles a range query that the service truncated to fit the binder
 * buffer. Pages continue from the last returned `startMs`; overlapping pages
 * are de-duplicated by id. Stops when a reply is provably complete, when a page
 * adds nothing new (many events sharing one `startMs` beyond a page), or after
 * [maxPages].
 */
internal class RangeQueryPager(
  private val maxPages: Int = 64,
  private val query: suspend (fromMs: Long, toMs: Long, types: Array<String>) -> String,
) {
  suspend fun fetch(fromMs: Long, toMs: Long, types: Array<String>): List<Event> {
    if (toMs <= fromMs) return emptyList()
    val byId = LinkedHashMap<String, Event>()
    var cursor = fromMs
    for (page in 0 until maxPages) {
      val reply = query(cursor, toMs, types)
      val events = ContextJson.decodeEvents(reply)
      var added = 0
      for (event in events) if (byId.putIfAbsent(event.id, event) == null) added++
      if (events.isEmpty() || added == 0 || !ContextContract.replyMayBeTruncated(reply.length)) break
      cursor = maxOf(cursor, events.last().startMs)
    }
    return byId.values.sortedWith(compareBy<Event>({ it.startMs }, { it.id }))
  }
}
