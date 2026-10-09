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
  /** `Instances._ID`: one occurrence of a (possibly recurring) event. */
  val instanceId: Long,
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
)

/**
 * Pure mapping from [CalendarRow] to a `calendar.event` [Event].
 *
 * Payload: `{"instanceId":Long,"title":String,"location":String?,"allDay":Boolean,"calendar":String}`.
 *
 * **Id contract.** Events are immutable, so the id hashes everything the
 * payload shows that can change: `(instanceId, begin, end, title, location,
 * allDay)`. Re-reading an unchanged instance yields the same id (deduped on
 * insert); editing it yields a new event with a new id. Several events may
 * therefore share an `instanceId`: consumers (the distiller) must keep only the
 * one with the newest `createdMs` per `instanceId`.
 */
object CalendarEvents {
  const val SOURCE = "calendar_provider"
  const val NO_TITLE = "(no title)"

  /** The event for [row], or null when it is canceled or declined by the user. */
  fun toEvent(row: CalendarRow, nowMs: Long): Event? {
    if (row.status == Events.STATUS_CANCELED) return null
    if (row.selfAttendeeStatus == Attendees.ATTENDEE_STATUS_DECLINED) return null
    val title = row.title?.takeIf { it.isNotBlank() } ?: NO_TITLE
    val location = row.location?.takeIf { it.isNotBlank() }
    // A malformed row may lack END or end before BEGIN; clamp so it stays valid.
    val endMs = (row.endMs ?: row.beginMs).coerceAtLeast(row.beginMs)
    val type = EventTypes.CALENDAR_EVENT
    val payload = buildJsonObject {
      put("instanceId", row.instanceId)
      put("title", title)
      put("location", location)
      put("allDay", row.allDay)
      put("calendar", row.calendar.orEmpty())
    }
    return Event(
      id = EventIds.stable(type, row.instanceId, row.beginMs, endMs, title, location, row.allDay),
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
