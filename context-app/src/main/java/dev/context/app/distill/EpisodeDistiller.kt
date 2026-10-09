package dev.context.app.distill

import dev.context.core.ContextContract
import dev.context.core.db.ContextDatabase
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import java.time.ZoneId

/**
 * Loads the distiller's inputs from Room and hands them to [Distillation].
 *
 * Three reads, each paged by [ContextContract.QUERY_ROW_LIMIT]:
 * - health, usage and location events overlapping the last
 *   [Distillation.EPISODE_WINDOW_MS] plus a few hours of context (heart rate is
 *   not used yet, so not read);
 * - calendar events overlapping that window and the next 30 days, since the
 *   calendar collector logs upcoming events ahead of time and a moved
 *   meeting's newest version may lie well beyond `nextEvent`'s horizon;
 * - `kb.note` events of the last [Distillation.NOTES_WINDOW_MS].
 *
 * Blocking; call from a worker thread.
 */
class EpisodeDistiller(
  private val db: ContextDatabase,
  private val zone: () -> ZoneId,
) : Distiller {
  override fun distill(nowMs: Long): DistillOutput {
    val dao = db.events()
    val fromMs = nowMs - Distillation.EPISODE_WINDOW_MS
    // Reading a little before the window keeps a focus block that straddles its
    // start whole, so the block keeps its first session and therefore its id.
    val spanFromMs = fromMs - EDGE_CONTEXT_MS
    // +1: the DAO ranges are half-open, and an event starting exactly now counts.
    val untilNow = nowMs + 1

    val observed = paged(spanFromMs - ContextContract.MAX_EVENT_SPAN_MS) { lower, limit ->
      dao.overlappingOfTypes(spanFromMs, untilNow, lower, SPAN_TYPES, limit)
    }
    // Far enough ahead that a meeting rescheduled out of the next day is still
    // seen as superseded instead of resurfacing at its old time.
    val calendar = paged(fromMs - ContextContract.MAX_EVENT_SPAN_MS) { lower, limit ->
      dao.overlappingOfTypes(fromMs, nowMs + CALENDAR_LOOKAHEAD_MS, lower, CALENDAR_TYPES, limit)
    }
    val notes = paged(nowMs - Distillation.NOTES_WINDOW_MS) { lower, limit ->
      dao.startedInOfTypes(lower, untilNow, NOTE_TYPES, limit)
    }
    return Distillation.distill(observed + calendar + notes, nowMs, zone())
  }

  /**
   * Pages a query ordered by `(startMs, id)` whose only moving bound is the
   * lowest `startMs` it returns. Each next page restarts at the last row's
   * `startMs` (re-reading rows with that start, deduped by id). If a whole
   * page shares one `startMs` it skips past it rather than looping forever,
   * dropping the overflow, which no real collector produces.
   */
  private fun paged(firstLower: Long, query: (lower: Long, limit: Int) -> List<Event>): List<Event> {
    val limit = ContextContract.QUERY_ROW_LIMIT
    val byId = LinkedHashMap<String, Event>()
    var lower = firstLower
    while (true) {
      val page = query(lower, limit)
      page.forEach { byId.putIfAbsent(it.id, it) }
      if (page.size < limit) break
      val last = page.last().startMs
      lower = if (last > lower) last else last + 1
    }
    return byId.values.toList()
  }

  private companion object {
    const val EDGE_CONTEXT_MS = 6 * Distillation.HOUR_MS
    const val CALENDAR_LOOKAHEAD_MS = 30 * Distillation.DAY_MS

    val SPAN_TYPES = listOf(
      EventTypes.HEALTH_SLEEP,
      EventTypes.HEALTH_STEPS,
      EventTypes.USAGE_SESSION,
      EventTypes.LOCATION_VISIT,
      EventTypes.LOCATION_ACTIVITY,
    )
    val CALENDAR_TYPES = listOf(EventTypes.CALENDAR_EVENT)
    val NOTE_TYPES = listOf(EventTypes.KB_NOTE)
  }
}
