package dev.context.app.collect.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract.Instances
import dev.context.app.collect.Collector
import dev.context.core.model.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Logs calendar instances from the system `CalendarContract` provider as
 * `calendar.event`s (one per instance; see [CalendarEvents] for the mapping and
 * the id contract).
 *
 * Each run reads instances overlapping `[fromMs, toMs + 7 days)`, so upcoming
 * events are logged ahead of time: the distiller needs them for `nextEvent`.
 * Only visible calendars are read; canceled instances and events the user
 * declined are skipped.
 *
 * Known limitation: events are immutable, so an instance that was already
 * logged and is later deleted or canceled stays in the store. The distiller
 * only surfaces future instances in `nextEvent`, so a stale one is visible
 * until its start time at most.
 */
class CalendarCollector(private val context: Context) : Collector {
  override val key = "calendar"

  override suspend fun missingPermissions(): List<String> =
    if (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
      emptyList()
    } else {
      listOf(Manifest.permission.READ_CALENDAR)
    }

  override suspend fun collect(fromMs: Long, toMs: Long): List<Event> = withContext(Dispatchers.IO) {
    val nowMs = System.currentTimeMillis()
    val uri = Instances.CONTENT_URI.buildUpon().also {
      ContentUris.appendId(it, fromMs)
      ContentUris.appendId(it, toMs + LOOKAHEAD_MS)
    }.build()
    val rows = context.contentResolver.query(uri, PROJECTION, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC")
      ?.use { cursor -> readRows(cursor) }
      .orEmpty()
    rows.mapNotNull { CalendarEvents.toEvent(it, nowMs) }
  }

  private fun readRows(cursor: Cursor): List<CalendarRow> {
    val eventId = cursor.getColumnIndexOrThrow(Instances.EVENT_ID)
    val begin = cursor.getColumnIndexOrThrow(Instances.BEGIN)
    val end = cursor.getColumnIndexOrThrow(Instances.END)
    val title = cursor.getColumnIndexOrThrow(Instances.TITLE)
    val location = cursor.getColumnIndexOrThrow(Instances.EVENT_LOCATION)
    val allDay = cursor.getColumnIndexOrThrow(Instances.ALL_DAY)
    val calendar = cursor.getColumnIndexOrThrow(Instances.CALENDAR_DISPLAY_NAME)
    val status = cursor.getColumnIndexOrThrow(Instances.STATUS)
    val selfStatus = cursor.getColumnIndexOrThrow(Instances.SELF_ATTENDEE_STATUS)
    val rrule = cursor.getColumnIndexOrThrow(Instances.RRULE)
    val rdate = cursor.getColumnIndexOrThrow(Instances.RDATE)
    val originalId = cursor.getColumnIndexOrThrow(Instances.ORIGINAL_ID)
    val originalTime = cursor.getColumnIndexOrThrow(Instances.ORIGINAL_INSTANCE_TIME)
    val rows = ArrayList<CalendarRow>(cursor.count.coerceAtLeast(0))
    while (cursor.moveToNext()) {
      rows += CalendarRow(
        eventId = cursor.getLong(eventId),
        beginMs = cursor.getLong(begin),
        endMs = if (cursor.isNull(end)) null else cursor.getLong(end),
        title = cursor.stringOrNull(title),
        location = cursor.stringOrNull(location),
        allDay = !cursor.isNull(allDay) && cursor.getInt(allDay) != 0,
        calendar = cursor.stringOrNull(calendar),
        status = if (cursor.isNull(status)) null else cursor.getInt(status),
        selfAttendeeStatus = if (cursor.isNull(selfStatus)) null else cursor.getInt(selfStatus),
        recurring = !cursor.stringOrNull(rrule).isNullOrBlank() || !cursor.stringOrNull(rdate).isNullOrBlank(),
        originalId = cursor.stringOrNull(originalId)?.takeIf { it.isNotBlank() },
        originalInstanceTimeMs = if (cursor.isNull(originalTime)) null else cursor.getLong(originalTime),
      )
    }
    return rows
  }

  private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)

  companion object {
    /** How far past the run's end upcoming instances are read. */
    const val LOOKAHEAD_MS = 7L * 24 * 60 * 60 * 1000

    // Not Instances._ID: the provider renumbers it whenever it rebuilds its
    // instance cache (event edits, time zone changes). See CalendarEvents.
    private val PROJECTION = arrayOf(
      Instances.EVENT_ID,
      Instances.BEGIN,
      Instances.END,
      Instances.TITLE,
      Instances.EVENT_LOCATION,
      Instances.ALL_DAY,
      Instances.CALENDAR_DISPLAY_NAME,
      Instances.STATUS,
      Instances.SELF_ATTENDEE_STATUS,
      Instances.RRULE,
      Instances.RDATE,
      Instances.ORIGINAL_ID,
      Instances.ORIGINAL_INSTANCE_TIME,
    )
  }
}
