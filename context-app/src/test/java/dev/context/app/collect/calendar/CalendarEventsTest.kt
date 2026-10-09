package dev.context.app.collect.calendar

import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Events
import dev.context.core.model.EventTypes
import dev.context.core.model.Sensitivity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarEventsTest {
  private val row = CalendarRow(
    instanceId = 42,
    beginMs = 1_000_000,
    endMs = 4_600_000,
    title = "Standup",
    location = "Room 1",
    allDay = false,
    calendar = "Work",
    status = Events.STATUS_CONFIRMED,
    selfAttendeeStatus = Attendees.ATTENDEE_STATUS_ACCEPTED,
  )

  private fun map(r: CalendarRow, now: Long = 5_000_000) = checkNotNull(CalendarEvents.toEvent(r, now))

  @Test
  fun mapsFieldsAndPayload() {
    val e = map(row)
    assertEquals(EventTypes.CALENDAR_EVENT, e.type)
    assertEquals(1_000_000L, e.startMs)
    assertEquals(4_600_000L, e.endMs)
    assertEquals("calendar_provider", e.source)
    assertEquals(Sensitivity.PERSONAL, e.sensitivity)
    assertEquals(5_000_000L, e.createdMs)
    val p = Json.parseToJsonElement(e.payload).jsonObject
    assertEquals(42L, p.getValue("instanceId").jsonPrimitive.long)
    assertEquals("Standup", p.getValue("title").jsonPrimitive.content)
    assertEquals("Room 1", p.getValue("location").jsonPrimitive.content)
    assertEquals(false, p.getValue("allDay").jsonPrimitive.boolean)
    assertEquals("Work", p.getValue("calendar").jsonPrimitive.content)
  }

  @Test
  fun unchangedRowKeepsItsIdAcrossRuns() {
    assertEquals(map(row, now = 1).id, map(row, now = 2).id)
  }

  @Test
  fun editedRowGetsANewId() {
    val base = map(row).id
    assertNotEquals(base, map(row.copy(title = "Retro")).id)
    assertNotEquals(base, map(row.copy(beginMs = 1_500_000)).id)
    assertNotEquals(base, map(row.copy(endMs = 5_000_000)).id)
    assertNotEquals(base, map(row.copy(location = null)).id)
    assertNotEquals(base, map(row.copy(allDay = true)).id)
    assertNotEquals(base, map(row.copy(instanceId = 43)).id)
    // Not part of the event's identity: calendar name and attendee bookkeeping.
    assertEquals(base, map(row.copy(selfAttendeeStatus = null)).id)
  }

  @Test
  fun nullTitleAndLocationFallBack() {
    val e = map(row.copy(title = null, location = null, calendar = null))
    val p = Json.parseToJsonElement(e.payload).jsonObject
    assertEquals("(no title)", p.getValue("title").jsonPrimitive.content)
    assertEquals(JsonNull, p.getValue("location"))
    assertEquals("", p.getValue("calendar").jsonPrimitive.content)
    // Blank strings are treated as missing.
    assertEquals(e.id, map(row.copy(title = " ", location = "", calendar = null)).id)
  }

  @Test
  fun canceledAndDeclinedAreSkipped() {
    assertNull(CalendarEvents.toEvent(row.copy(status = Events.STATUS_CANCELED), 0))
    assertNull(CalendarEvents.toEvent(row.copy(selfAttendeeStatus = Attendees.ATTENDEE_STATUS_DECLINED), 0))
    assertNotNull(CalendarEvents.toEvent(row.copy(status = null, selfAttendeeStatus = null), 0))
    assertNotNull(CalendarEvents.toEvent(row.copy(status = Events.STATUS_TENTATIVE), 0))
  }

  @Test
  fun allDayKeepsProviderBoundsAndMalformedEndIsClamped() {
    val day = 86_400_000L
    val allDay = map(row.copy(allDay = true, beginMs = 20 * day, endMs = 21 * day))
    assertEquals(20 * day, allDay.startMs)
    assertEquals(21 * day, allDay.endMs)
    assertEquals(true, Json.parseToJsonElement(allDay.payload).jsonObject.getValue("allDay").jsonPrimitive.boolean)
    assertEquals(row.beginMs, map(row.copy(endMs = null)).endMs)
    assertEquals(row.beginMs, map(row.copy(endMs = row.beginMs - 1)).endMs)
  }
}
