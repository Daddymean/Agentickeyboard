package dev.context.app.collect.calendar

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import dev.context.app.collect.EventIds
import dev.context.app.collect.SensitivityPolicy
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One `CalendarContract.Instances` row, as read by [CalendarCollector]. */
data class CalendarRow(
  /** `Instances.EVENT_ID`: the event this occurrence belongs to. */
  val eventId: Long,
  val beginMs: Long,
  val endMs: Long?,
  val title: String?,
  val location: String?,
  /** For all-day rows the provider gives UTC midnights; they are kept as-is. */
  val allDay: Boolean,
  /** Display name of the owning calendar. */
  val calendar: String?,
  /** `Events.STATUS_*`, or null when unset. */
  val status: Int?,
  /** `Attendees.ATTENDEE_STATUS_*` of the device user, or null when unset. */
  val selfAttendeeStatus: Int?,
  /** True when the event repeats (`RRULE` or `RDATE` set). */
  val recurring: Boolean = false,
  /** `ORIGINAL_ID`: for an exception (one moved/edited occurrence), its recurring parent. */
  val originalId: String? = null,
  /** `ORIGINAL_INSTANCE_TIME`: the parent occurrence's original start, for an exception. */
  val originalInstanceTimeMs: Long? = null,
)

/**
 * Pure mapping from [CalendarRow] to a `calendar.event` [Event].
 *
 * Payload: `{"occurrence":String,"title":String,"location":String?,"allDay":Boolean,"calendar":String}`.
 *
 * **Occurrence identity.** [occurrenceKey] names one occurrence stably across
 * edits, moves and the provider rebuilding its instance cache (which renumbers
 * `Instances._ID`, so that column is never used):
 * - a single event: its `EVENT_ID`;
 * - an occurrence of a recurring event: `EVENT_ID@BEGIN`;
 * - an exception (that occurrence moved or edited): `ORIGINAL_ID@ORIGINAL_INSTANCE_TIME`,
 *   which equals the key it had before the change.
 *
 * **Id contract.** Events are immutable, so the id hashes the occurrence plus
 * everything the payload shows that can change: `(occurrence, begin, end,
 * title, location, allDay)`. Re-reading an unchanged occurrence yields the same
 * id (deduped on insert); editing or moving it yields a new event. Several
 * events may therefore share an `occurrence`: consumers (the distiller) must
 * keep only the one with the newest `createdMs` per `occurrence`.
 */
object CalendarEvents {
  const val SOURCE = "calendar_provider"
  const val NO_TITLE = "(no title)"

  /** Stable identity of the occurrence [row] describes; see the class KDoc. */
  fun occurrenceKey(row: CalendarRow): String = when {
    row.originalId != null && row.originalInstanceTimeMs != null -> "${row.originalId}@${row.originalInstanceTimeMs}"
    row.recurring -> "${row.eventId}@${row.beginMs}"
    else -> "${row.eventId}"
  }

  /** The event for [row], or null when it is canceled or declined by the user. */
  fun toEvent(row: CalendarRow, nowMs: Long): Event? {
    if (row.status == Events.STATUS_CANCELED) return null
    if (row.selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED) return null
    val title = row.title?.takeIf { it.isNotBlank() } ?: NO_TITLE
    val location = row.location?.takeIf { it.isNotBlank() }
    // A malformed row may lack END or end before BEGIN; clamp so it stays valid.
    val endMs = (row.endMs ?: row.beginMs).coerceAtLeast(row.beginMs)
    val type = EventTypes.CALENDAR_EVENT
    val occurrence = occurrenceKey(row)
    val payload = buildJsonObject {
      put("occurrence", occurrence)
      put("title", title)
      put("location", location)
      put("allDay", row.allDay)
      put("calendar", row.calendar.orEmpty())
    }
    return Event(
      id = EventIds.stable(type, occurrence, row.beginMs, endMs, title, location, row.allDay),
      type = type,
      startMs = row.beginMs,
      endMs = endMs,
      source = SOURCE,
      payload = payload.toString(),
      sensitivity = SensitivityPolicy.forType(type),
      createdMs = nowMs,
    )
  }
}
