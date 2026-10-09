package dev.context.app.distill

import dev.context.app.collect.EventIds
import dev.context.core.model.Episode
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import dev.context.core.model.NextEvent
import dev.context.core.model.Sensitivity
import dev.context.core.model.Snapshot
import dev.context.core.model.Today
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Episode kinds the distiller emits. */
object EpisodeKinds {
  const val SLEEP = "sleep"
  const val VISIT = "visit"
  const val FOCUS = "focus"
  const val MEETING = "meeting"
  const val ACTIVITY = "activity"
}

/**
 * The distiller's pure core: events in, episodes and both snapshots out. No
 * clock, no database, no model inference; [EpisodeDistiller] loads the inputs.
 *
 * Payloads come from collectors written independently, so every field is read
 * defensively: a payload that is not a JSON object drops its event, and a
 * missing or mistyped field falls back or drops the event, never throws.
 */
object Distillation {
  const val HOUR_MS = 60L * 60 * 1000
  const val DAY_MS = 24 * HOUR_MS

  /** Episodes are derived for events overlapping the last this-many ms. */
  const val EPISODE_WINDOW_MS = 48 * HOUR_MS

  /** `kb.note` events this recent feed `recentNotes`. */
  const val NOTES_WINDOW_MS = 7 * DAY_MS

  /** `nextEvent` looks this far ahead. */
  const val NEXT_EVENT_HORIZON_MS = DAY_MS

  /** Usage sessions closer than this are merged into one focus block. */
  const val FOCUS_MAX_GAP_MS = 5L * 60 * 1000

  /** Focus blocks shorter than this are dropped. */
  const val FOCUS_MIN_MS = 15L * 60 * 1000

  /** Activity segments shorter than this are dropped. */
  const val ACTIVITY_MIN_MS = 10L * 60 * 1000

  const val TOP_APPS = 5
  const val RECENT_NOTES = 5

  private val ACTIVITY_TITLES = mapOf(
    "walking" to "Walking",
    "running" to "Running",
    "cycling" to "Cycling",
    "in_vehicle" to "In vehicle",
  )

  /**
   * Derives episodes from all of [events] and two snapshots for the local day
   * of [nowMs] in [zone]: `local` from every event, `sync` from only those
   * with sensitivity <= [Sensitivity.SYNC_MAX] (its `activeEpisode` from the
   * derived episodes whose own sensitivity allows sync).
   */
  fun distill(events: List<Event>, nowMs: Long, zone: ZoneId): DistillOutput {
    val all = Inputs.parse(events)
    val episodes = episodes(all, nowMs)
    // The sync snapshot's active episode is picked from the stored episodes that
    // are syncable as a whole, so its id always names a row sync can upload.
    val syncableEpisodes = episodes.filter { Sensitivity.isSyncable(it.sensitivity) }
    return DistillOutput(
      episodes = episodes,
      local = snapshot(all, episodes, nowMs, zone),
      sync = snapshot(all.syncable(), syncableEpisodes, nowMs, zone),
    )
  }

  // ---------------------------------------------------------------- inputs

  private class Sleep(val event: Event, val endMs: Long, val minutes: Double)
  private class Steps(val event: Event, val count: Long)
  private class Usage(val event: Event, val endMs: Long, val label: String)
  private class Visit(val event: Event, val endMs: Long, val label: String?)
  private class Activity(val event: Event, val endMs: Long, val title: String)
  private class Calendar(
    val event: Event,
    val versions: List<Event>,
    val title: String,
    val location: String?,
    val allDay: Boolean,
  )
  private class Note(val event: Event, val text: String)

  /** Typed, validated inputs. Calendar edits are already collapsed per instance. */
  private class Inputs(
    val sleep: List<Sleep>,
    val steps: List<Steps>,
    val usage: List<Usage>,
    val visits: List<Visit>,
    val activities: List<Activity>,
    val calendar: List<Calendar>,
    val notes: List<Note>,
  ) {
    /**
     * Inputs with sensitivity <= [Sensitivity.SYNC_MAX]. A calendar instance is
     * kept only when its newest version is syncable, so a private edit hides
     * the meeting instead of resurfacing an older, stale public version.
     */
    fun syncable(): Inputs {
      val ok = { e: Event -> Sensitivity.isSyncable(e.sensitivity) }
      return Inputs(
        sleep = sleep.filter { ok(it.event) },
        steps = steps.filter { ok(it.event) },
        usage = usage.filter { ok(it.event) },
        visits = visits.filter { ok(it.event) },
        activities = activities.filter { ok(it.event) },
        calendar = calendar.filter { ok(it.event) },
        notes = notes.filter { ok(it.event) },
      )
    }

    companion object {
      fun parse(raw: List<Event>): Inputs {
        val events = raw.distinctBy { it.id }.sortedWith(EVENT_ORDER)
        val sleep = mutableListOf<Sleep>()
        val steps = mutableListOf<Steps>()
        val usage = mutableListOf<Usage>()
        val visits = mutableListOf<Visit>()
        val activities = mutableListOf<Activity>()
        val calendar = mutableListOf<Pair<Event, JsonObject>>()
        val notes = mutableListOf<Note>()
        for (e in events) {
          val p = payload(e) ?: continue
          when (e.type) {
            EventTypes.HEALTH_SLEEP -> {
              val end = closedEnd(e) ?: continue
              val minutes = p.double("durationMin")?.takeIf { it > 0 }
                ?: ((end - e.startMs) / 60_000.0)
              sleep += Sleep(e, end, minutes)
            }
            EventTypes.HEALTH_STEPS -> {
              val count = p.long("count")?.takeIf { it >= 0 } ?: continue
              steps += Steps(e, count)
            }
            EventTypes.USAGE_SESSION -> {
              val end = closedEnd(e) ?: continue
              val label = p.string("label") ?: p.string("package") ?: continue
              usage += Usage(e, end, label)
            }
            EventTypes.LOCATION_VISIT -> {
              val end = closedEnd(e) ?: continue
              visits += Visit(e, end, p.string("label"))
            }
            EventTypes.LOCATION_ACTIVITY -> {
              val end = closedEnd(e) ?: continue
              val title = p.string("activity")?.let { ACTIVITY_TITLES[it] } ?: continue
              activities += Activity(e, end, title)
            }
            EventTypes.CALENDAR_EVENT -> calendar += e to p
            EventTypes.KB_NOTE -> {
              val text = p.string("text") ?: continue
              notes += Note(e, text)
            }
          }
        }
        return Inputs(sleep, steps, usage, visits, activities, dedupeCalendar(calendar), notes)
      }

      /**
       * One entry per calendar instance: the version with the newest
       * `createdMs` supplies the content, and every version is a member so
       * the episode id (from the earliest-created version) survives edits.
       */
      private fun dedupeCalendar(rows: List<Pair<Event, JsonObject>>): List<Calendar> =
        rows.groupBy { (e, p) ->
          // "occurrence" is the stable key; "instanceId" only exists on events
          // logged before it and still groups those among themselves.
          p.string("occurrence")?.let { "o:$it" }
            ?: p.long("instanceId")?.let { "i:$it" }
            ?: "e:${e.id}"
        }
          .values
          .map { versions ->
            val byAge = versions.sortedWith(compareBy({ it.first.createdMs }, { it.first.id }))
            val (latest, p) = byAge.last()
            Calendar(
              event = latest,
              versions = byAge.map { it.first },
              title = p.string("title") ?: "Event",
              location = p.string("location"),
              allDay = p.boolean("allDay") ?: false,
            )
          }
          .sortedWith(compareBy({ it.event.startMs }, { it.event.id }))
    }
  }

  // -------------------------------------------------------------- episodes

  private fun episodes(inputs: Inputs, nowMs: Long): List<Episode> {
    val out = mutableListOf<Episode>()
    inputs.sleep.forEach { s ->
      out += episode(EpisodeKinds.SLEEP, listOf(s.event), s.event.startMs, s.endMs, "Sleep", hours(s.minutes))
    }
    inputs.visits.forEach { v ->
      out += episode(
        EpisodeKinds.VISIT, listOf(v.event), v.event.startMs, v.endMs,
        v.label ?: "Somewhere", duration(v.endMs - v.event.startMs),
      )
    }
    out += focusBlocks(inputs.usage)
    inputs.calendar.filter { !it.allDay }.forEach { c ->
      val end = c.event.endMs?.takeIf { it > c.event.startMs } ?: return@forEach
      val summary = listOfNotNull(duration(end - c.event.startMs), c.location).joinToString(" · ")
      // The id comes from the earliest-created version (first in `versions`), so
      // rescheduling a meeting updates its episode instead of adding another.
      out += episode(EpisodeKinds.MEETING, c.versions, c.event.startMs, end, c.title, summary)
    }
    inputs.activities.filter { it.endMs - it.event.startMs >= ACTIVITY_MIN_MS }.forEach { a ->
      out += episode(
        EpisodeKinds.ACTIVITY, listOf(a.event), a.event.startMs, a.endMs,
        a.title, duration(a.endMs - a.event.startMs),
      )
    }
    // Past or in progress, and inside the recomputed window; nothing scheduled ahead.
    return out
      .filter { it.startMs <= nowMs && it.endMs > nowMs - EPISODE_WINDOW_MS }
      .sortedWith(compareBy({ it.startMs }, { it.id }))
  }

  private fun focusBlocks(usage: List<Usage>): List<Episode> {
    val blocks = mutableListOf<MutableList<Usage>>()
    var blockEnd = Long.MIN_VALUE
    for (u in usage) { // already in start order
      val current = blocks.lastOrNull()
      if (current != null && u.event.startMs - blockEnd < FOCUS_MAX_GAP_MS) {
        current += u
        blockEnd = max(blockEnd, u.endMs)
      } else {
        blocks += mutableListOf(u)
        blockEnd = u.endMs
      }
    }
    return blocks.mapNotNull { block ->
      val start = block.first().event.startMs
      val end = block.maxOf { it.endMs }
      if (end - start < FOCUS_MIN_MS) return@mapNotNull null
      val labels = block.groupBy { it.label }
        .mapValues { (_, us) -> us.sumOf { it.endMs - it.event.startMs } }
        .entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
        .map { it.key }
      val summary = labels.take(2).joinToString(", ") + " · " + duration(end - start)
      episode(EpisodeKinds.FOCUS, block.map { it.event }, start, end, labels.first(), summary)
    }
  }

  /** [members] must be non-empty; the first one names the episode. */
  private fun episode(
    kind: String,
    members: List<Event>,
    startMs: Long,
    endMs: Long,
    title: String,
    summary: String,
  ) = Episode(
    id = EventIds.stable("episode", kind, members.first().id),
    startMs = startMs,
    endMs = endMs,
    kind = kind,
    title = title,
    summary = summary,
    eventIds = JsonArray(members.map { JsonPrimitive(it.id) }).toString(),
    sensitivity = members.maxOf { it.sensitivity },
  )

  // -------------------------------------------------------------- snapshot

  private fun snapshot(inputs: Inputs, episodes: List<Episode>, nowMs: Long, zone: ZoneId): Snapshot {
    val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val todayEnd = min(dayEnd, nowMs + 1) // [dayStart, todayEnd) = today up to and including now
    var maxSensitivity = Sensitivity.PUBLIC
    fun used(e: Event) {
      maxSensitivity = max(maxSensitivity, e.sensitivity)
    }

    val sleepToday = inputs.sleep.filter { it.endMs in dayStart until todayEnd }
    sleepToday.forEach { used(it.event) }
    val sleepHours = sleepToday.takeIf { it.isNotEmpty() }
      ?.let { s -> ((s.sumOf { it.minutes } / 60.0) * 10).roundToLong() / 10f }

    val stepsToday = inputs.steps.filter { it.event.startMs in dayStart until todayEnd }
    stepsToday.forEach { used(it.event) }
    val steps = stepsToday.takeIf { it.isNotEmpty() }
      ?.let { s -> s.sumOf { it.count }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }

    val appTime = HashMap<String, Long>()
    inputs.usage.forEach { u ->
      val ms = min(u.endMs, todayEnd) - max(u.event.startMs, dayStart)
      if (ms > 0) {
        appTime[u.label] = (appTime[u.label] ?: 0L) + ms
        used(u.event)
      }
    }
    val topApps = appTime.entries
      .sortedWith(compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key })
      .take(TOP_APPS)
      .map { it.key }

    val places = inputs.visits
      .filter { it.label != null && it.event.startMs < todayEnd && it.endMs > dayStart }
      .onEach { used(it.event) }
      .mapNotNull { it.label }
      .distinct()

    val active = episodes
      .filter { it.startMs <= nowMs && nowMs < it.endMs }
      .maxWithOrNull(compareBy<Episode>({ it.startMs }, { it.id }))
    active?.let { maxSensitivity = max(maxSensitivity, it.sensitivity) }

    val next = inputs.calendar
      .filter { !it.allDay && it.event.startMs > nowMs && it.event.startMs < nowMs + NEXT_EVENT_HORIZON_MS }
      .minWithOrNull(compareBy<Calendar>({ it.event.startMs }, { it.event.id }))
    next?.let { used(it.event) }

    val notes = inputs.notes
      .filter { it.event.startMs in (nowMs - NOTES_WINDOW_MS)..nowMs }
      .sortedWith(
        compareByDescending<Note> { it.event.startMs }.thenByDescending { it.event.createdMs }.thenBy { it.event.id },
      )
      .take(RECENT_NOTES)
    notes.forEach { used(it.event) }

    return Snapshot(
      generatedAtMs = nowMs,
      today = Today(sleepHours = sleepHours, steps = steps, topApps = topApps, places = places),
      activeEpisode = active,
      nextEvent = next?.let { NextEvent(it.title, it.event.startMs) },
      recentNotes = notes.map { it.text },
      maxSensitivity = maxSensitivity,
    )
  }

  // --------------------------------------------------------------- helpers

  private val EVENT_ORDER = compareBy<Event>({ it.startMs }, { it.id })

  private fun payload(e: Event): JsonObject? =
    try {
      Json.parseToJsonElement(e.payload) as? JsonObject
    } catch (_: Exception) {
      null
    }

  /** End of a closed span, or null for point events and empty or inverted spans. */
  private fun closedEnd(e: Event): Long? = e.endMs?.takeIf { it > e.startMs }

  private fun JsonObject.primitive(key: String): JsonPrimitive? = get(key) as? JsonPrimitive

  /** Non-blank string value; numbers and JSON null don't count. */
  private fun JsonObject.string(key: String): String? =
    primitive(key)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

  private fun JsonObject.long(key: String): Long? =
    primitive(key)?.takeIf { !it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

  private fun JsonObject.double(key: String): Double? =
    primitive(key)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

  private fun JsonObject.boolean(key: String): Boolean? =
    primitive(key)?.takeIf { !it.isString }?.booleanOrNull

  /** "7.4 h". */
  private fun hours(minutes: Double): String = "%.1f h".format(Locale.ROOT, minutes / 60.0)

  /** "45 min", "2 h", "1 h 20 min". */
  internal fun duration(ms: Long): String {
    val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(0)
    val h = minutes / 60
    val m = minutes % 60
    return when {
      h == 0L -> "$m min"
      m == 0L -> "$h h"
      else -> "$h h $m min"
    }
  }
}
