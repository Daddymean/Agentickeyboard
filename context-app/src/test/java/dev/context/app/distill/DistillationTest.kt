package dev.context.app.distill

import dev.context.app.collect.EventIds
import dev.context.core.model.Episode
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import dev.context.core.model.NextEvent
import dev.context.core.model.Sensitivity
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DistillationTest {
  // A non-UTC zone: local midnight is 04:00Z, so UTC and local days disagree.
  private val zone = ZoneId.of("America/New_York")

  /** Local wall-clock time on 2026-10-09 (EDT), shifted by [days]. */
  private fun at(hour: Int, minute: Int = 0, days: Long = 0): Long =
    ZonedDateTime.of(2026, 10, 9, hour, minute, 0, 0, zone).plusDays(days).toInstant().toEpochMilli()

  private val now = at(15)

  private fun ev(
    id: String,
    type: String,
    start: Long,
    end: Long?,
    payload: String,
    sensitivity: Int = Sensitivity.PERSONAL,
    created: Long = start,
  ) = Event(
    id = id, type = type, startMs = start, endMs = end, source = "test",
    payload = payload, sensitivity = sensitivity, createdMs = created,
  )

  private fun sleep(id: String, start: Long, end: Long, minutes: Int? = null, s: Int = Sensitivity.PERSONAL) =
    ev(id, EventTypes.HEALTH_SLEEP, start, end, minutes?.let { """{"durationMin":$it}""" } ?: "{}", s)

  private fun steps(id: String, start: Long, count: Int, s: Int = Sensitivity.PUBLIC) =
    ev(id, EventTypes.HEALTH_STEPS, start, start + Distillation.HOUR_MS, """{"count":$count}""", s)

  private fun usage(id: String, start: Long, end: Long, label: String, s: Int = Sensitivity.PERSONAL) =
    ev(id, EventTypes.USAGE_SESSION, start, end, """{"package":"pkg.$label","label":"$label"}""", s)

  private fun visit(id: String, start: Long, end: Long, label: String?, s: Int = Sensitivity.PERSONAL) =
    ev(
      id, EventTypes.LOCATION_VISIT, start, end,
      """{"lat":40.7,"lng":-74.0,"radiusM":80,"label":${label?.let { "\"$it\"" } ?: "null"}}""", s,
    )

  private fun activity(id: String, start: Long, end: Long, kind: String) =
    ev(id, EventTypes.LOCATION_ACTIVITY, start, end, """{"activity":"$kind"}""")

  private fun calendar(
    id: String,
    start: Long,
    end: Long,
    title: String,
    instanceId: Long,
    allDay: Boolean = false,
    created: Long = start - Distillation.DAY_MS,
    s: Int = Sensitivity.PERSONAL,
  ) = ev(
    id, EventTypes.CALENDAR_EVENT, start, end,
    """{"instanceId":$instanceId,"title":"$title","location":null,"allDay":$allDay,"calendar":"Work"}""",
    s, created,
  )

  private fun note(id: String, start: Long, text: String, s: Int = Sensitivity.PERSONAL) =
    ev(id, EventTypes.KB_NOTE, start, null, """{"text":"$text"}""", s)

  private fun distill(vararg events: Event) = Distillation.distill(events.toList(), now, zone)

  private fun List<Episode>.ofKind(kind: String) = filter { it.kind == kind }

  // ------------------------------------------------------------------ sleep

  @Test
  fun sleepBecomesAnEpisodeAndCountsTowardToday() {
    val out = distill(sleep("s1", at(23, days = -1), at(6, 30), minutes = 444))
    val ep = out.episodes.single()
    assertEquals(EpisodeKinds.SLEEP, ep.kind)
    assertEquals("Sleep", ep.title)
    assertEquals("7.4 h", ep.summary)
    assertEquals("""["s1"]""", ep.eventIds)
    assertEquals(EventIds.stable("episode", EpisodeKinds.SLEEP, "s1"), ep.id)
    assertEquals(at(23, days = -1), ep.startMs)
    assertEquals(at(6, 30), ep.endMs)
    assertEquals(7.4f, out.local.today.sleepHours)
  }

  @Test
  fun sleepWithoutDurationFallsBackToItsSpan() {
    val out = distill(sleep("s1", at(0), at(7, 30)))
    assertEquals("7.5 h", out.episodes.single().summary)
    assertEquals(7.5f, out.local.today.sleepHours)
  }

  @Test
  fun sleepEndingBeforeLocalMidnightIsNotToday() {
    // 23:59 EDT yesterday is already "today" in UTC.
    val out = distill(sleep("s1", at(16, days = -1), at(23, 59, days = -1), minutes = 400))
    assertNull(out.local.today.sleepHours)
    assertEquals(1, out.episodes.size)
  }

  @Test
  fun sleepHoursSumsSessionsEndingToday() {
    val out = distill(
      sleep("night", at(23, days = -1), at(6), minutes = 360),
      sleep("nap", at(13), at(13, 45), minutes = 45),
    )
    assertEquals(6.8f, out.local.today.sleepHours)
  }

  // ------------------------------------------------------------------ steps

  @Test
  fun stepsSumOnlyTodaysLocalBuckets() {
    val out = distill(
      steps("y", at(23, days = -1), 1000),
      steps("a", at(0), 200),
      steps("b", at(14), 3000),
      steps("future", at(16), 9999),
    )
    assertEquals(3200, out.local.today.steps)
  }

  @Test
  fun noStepBucketsMeansNullNotZero() {
    assertNull(distill().local.today.steps)
    assertEquals(0, distill(steps("a", at(9), 0)).local.today.steps)
  }

  // ------------------------------------------------------------------ visit

  @Test
  fun visitsBecomeEpisodesWithoutCoordinates() {
    val out = distill(
      visit("v1", at(8), at(9, 20), "Home"),
      visit("v2", at(9, 30), at(10), null),
    )
    val visits = out.episodes.ofKind(EpisodeKinds.VISIT)
    assertEquals(listOf("Home", "Somewhere"), visits.map { it.title })
    assertEquals(listOf("1 h 20 min", "30 min"), visits.map { it.summary })
    visits.forEach { assertTrue(!it.summary.contains("40.7") && !it.title.contains("40.7")) }
  }

  @Test
  fun placesAreDistinctChronologicalLabelsOfToday() {
    val out = distill(
      visit("old", at(10, days = -1), at(11, days = -1), "Gym"),
      visit("v1", at(7), at(8), "Home"),
      visit("v2", at(9), at(12), "Office"),
      visit("v3", at(12, 10), at(12, 40), null),
      visit("v4", at(13), at(14), "Home"),
      visit("overnight", at(22, days = -1), at(1), "Hotel"),
    )
    assertEquals(listOf("Hotel", "Home", "Office"), out.local.today.places)
  }

  // ------------------------------------------------------------------ focus

  @Test
  fun usageSessionsMergeAcrossShortGapsIntoFocusBlocks() {
    val out = distill(
      usage("u1", at(9), at(9, 10), "Docs"),
      usage("u2", at(9, 14), at(9, 30), "Slack"), // 4 min gap: merged
      usage("u3", at(9, 35), at(9, 40), "Docs"), // 5 min gap: new block, too short
    )
    val focus = out.episodes.ofKind(EpisodeKinds.FOCUS).single()
    assertEquals(at(9), focus.startMs)
    assertEquals(at(9, 30), focus.endMs)
    assertEquals("Slack", focus.title)
    assertEquals("Slack, Docs · 30 min", focus.summary)
    assertEquals("""["u1","u2"]""", focus.eventIds)
    assertEquals(EventIds.stable("episode", EpisodeKinds.FOCUS, "u1"), focus.id)
  }

  @Test
  fun focusBlocksNeedFifteenMinutes() {
    val exactly = distill(usage("u1", at(9), at(9, 15), "Docs"))
    assertEquals(1, exactly.episodes.ofKind(EpisodeKinds.FOCUS).size)
    val short = distill(usage("u1", at(9), at(9, 14), "Docs"))
    assertTrue(short.episodes.ofKind(EpisodeKinds.FOCUS).isEmpty())
  }

  @Test
  fun aSessionNestedInsideAnotherDoesNotShrinkTheBlock() {
    val out = distill(
      usage("u1", at(9), at(10), "Docs"),
      usage("u2", at(9, 10), at(9, 20), "Slack"),
      usage("u3", at(10, 3), at(10, 20), "Docs"), // 3 min after u1 ends
    )
    val focus = out.episodes.ofKind(EpisodeKinds.FOCUS).single()
    assertEquals(at(10, 20), focus.endMs)
    assertEquals("Docs", focus.title)
  }

  @Test
  fun topAppsRankTodaysClippedUsage() {
    val out = distill(
      usage("y", at(23, days = -1), at(1), "Night"), // 1 h today
      usage("a", at(9), at(9, 50), "A"),
      usage("b", at(10), at(10, 40), "B"),
      usage("c", at(11), at(11, 30), "C"),
      usage("d", at(12), at(12, 20), "D"),
      usage("e", at(13), at(13, 10), "E"),
      usage("f", at(14), at(14, 5), "F"),
    )
    assertEquals(listOf("Night", "A", "B", "C", "D"), out.local.today.topApps)
  }

  // --------------------------------------------------------------- activity

  @Test
  fun activitySegmentsOfTenMinutesOrMoreExceptStill() {
    val out = distill(
      activity("a1", at(8), at(8, 10), "walking"),
      activity("a2", at(9), at(9, 9), "running"),
      activity("a3", at(10), at(11), "still"),
      activity("a4", at(11), at(11, 30), "teleporting"),
      activity("a5", at(12), at(12, 45), "in_vehicle"),
    )
    val acts = out.episodes.ofKind(EpisodeKinds.ACTIVITY)
    assertEquals(listOf("Walking", "In vehicle"), acts.map { it.title })
    assertEquals(listOf("10 min", "45 min"), acts.map { it.summary })
  }

  // ---------------------------------------------------------------- meeting

  @Test
  fun timedCalendarEventsBecomeMeetingsAndAllDayOnesDoNot() {
    val out = distill(
      calendar("c1", at(10), at(10, 45), "Standup", instanceId = 1),
      calendar("c2", at(0), at(0, days = 1), "Holiday", instanceId = 2, allDay = true),
    )
    val meeting = out.episodes.ofKind(EpisodeKinds.MEETING).single()
    assertEquals("Standup", meeting.title)
    assertEquals("45 min", meeting.summary)
  }

  @Test
  fun futureMeetingsAreNotEpisodesButFeedNextEvent() {
    val out = distill(
      calendar("later", at(17), at(18), "Review", instanceId = 1),
      calendar("soon", at(16), at(16, 30), "1:1", instanceId = 2),
      calendar("allday", at(15, 30), at(16), "Focus day", instanceId = 3, allDay = true),
      calendar("tomorrow", at(15, days = 1), at(16, days = 1), "Too far", instanceId = 4),
    )
    assertTrue(out.episodes.isEmpty())
    assertEquals(NextEvent("1:1", at(16)), out.local.nextEvent)
  }

  @Test
  fun nextEventIgnoresMeetingsStartingNowOrBeyondADay() {
    val out = distill(
      calendar("now", now, now + Distillation.HOUR_MS, "Now", instanceId = 1),
      calendar("edge", now + Distillation.DAY_MS, now + Distillation.DAY_MS + 1, "Edge", instanceId = 2),
    )
    assertNull(out.local.nextEvent)
  }

  @Test
  fun calendarEditsCollapseToTheNewestVersionPerInstance() {
    val out = distill(
      calendar("v1", at(16), at(17), "Old title", instanceId = 7, created = at(8)),
      calendar("v2", at(17), at(18), "New title", instanceId = 7, created = at(9)),
      calendar("p1", at(10), at(11), "Draft", instanceId = 8, created = at(7)),
      calendar("p2", at(10, 30), at(11, 30), "Final", instanceId = 8, created = at(8)),
    )
    assertEquals(NextEvent("New title", at(17)), out.local.nextEvent)
    val meeting = out.episodes.ofKind(EpisodeKinds.MEETING).single()
    assertEquals("Final", meeting.title)
    assertEquals(at(10, 30), meeting.startMs)
    assertEquals("""["p1","p2"]""", meeting.eventIds)
    // Named by the earliest-created version, so a reschedule updates in place.
    assertEquals(EventIds.stable("episode", EpisodeKinds.MEETING, "p1"), meeting.id)
  }

  @Test
  fun inProgressMeetingIsTheActiveEpisode() {
    val out = distill(
      visit("v", at(14), at(14, 50), "Office"),
      calendar("c", at(14, 30), at(15, 30), "Planning", instanceId = 1),
    )
    assertEquals("Planning", out.local.activeEpisode?.title)
    assertEquals(EpisodeKinds.MEETING, out.local.activeEpisode?.kind)
  }

  @Test
  fun latestStartWinsTheActiveEpisode() {
    val out = distill(
      calendar("c1", at(14), at(16), "Workshop", instanceId = 1),
      calendar("c2", at(14, 45), at(15, 15), "Call", instanceId = 2),
    )
    assertEquals("Call", out.local.activeEpisode?.title)
  }

  @Test
  fun noActiveEpisodeWhenEverythingHasEnded() {
    assertNull(distill(visit("v", at(13), now, "Office")).local.activeEpisode)
  }

  // ------------------------------------------------------------------ notes

  @Test
  fun recentNotesAreTheFiveNewestOfTheLastWeek() {
    val out = distill(
      note("old", now - 8 * Distillation.DAY_MS, "too old"),
      note("n1", at(9, days = -6), "one"),
      note("n2", at(9, days = -5), "two"),
      note("n3", at(9, days = -4), "three"),
      note("n4", at(9, days = -1), "four"),
      note("n5", at(9), "five"),
      note("n6", at(14), "six"),
      note("blank", at(14, 30), "   "),
    )
    assertEquals(listOf("six", "five", "four", "three", "two"), out.local.recentNotes)
  }

  // --------------------------------------------------------- ids and window

  @Test
  fun idsAreStableAcrossRuns() {
    val events = listOf(
      sleep("s", at(23, days = -1), at(6), 400),
      visit("v", at(8), at(9), "Home"),
      usage("u1", at(9), at(9, 20), "Docs"),
      activity("a", at(10), at(10, 20), "walking"),
      calendar("c", at(11), at(12), "Sync", instanceId = 3),
    )
    val first = Distillation.distill(events, now, zone)
    val second = Distillation.distill(events.reversed(), now, zone)
    assertEquals(5, first.episodes.size)
    assertEquals(first, second)
  }

  @Test
  fun aGrowingFocusBlockKeepsItsId() {
    val before = distill(usage("u1", at(9), at(9, 20), "Docs")).episodes.single()
    val after = distill(
      usage("u1", at(9), at(9, 20), "Docs"),
      usage("u2", at(9, 22), at(9, 40), "Docs"),
    ).episodes.single()
    assertEquals(before.id, after.id)
    assertEquals(at(9, 40), after.endMs)
  }

  @Test
  fun episodesOutsideTheWindowAreNotRederived() {
    val windowStart = now - Distillation.EPISODE_WINDOW_MS
    val out = distill(
      visit("gone", windowStart - Distillation.HOUR_MS, windowStart, "Old"),
      visit("kept", windowStart - Distillation.HOUR_MS, windowStart + 1, "Edge"),
    )
    assertEquals(listOf("Edge"), out.episodes.map { it.title })
  }

  @Test
  fun duplicateEventIdsAreReadOnce() {
    val s = steps("a", at(9), 100)
    assertEquals(100, distill(s, s).local.today.steps)
  }

  // ------------------------------------------------------------ sensitivity

  @Test
  fun episodeSensitivityIsTheMaxOfItsMembers() {
    val out = distill(
      usage("u1", at(9), at(9, 10), "Docs", s = Sensitivity.PUBLIC),
      usage("u2", at(9, 10), at(9, 30), "Bank", s = Sensitivity.PRIVATE),
    )
    assertEquals(Sensitivity.PRIVATE, out.episodes.single().sensitivity)
  }

  @Test
  fun syncSnapshotExcludesPrivateInputs() {
    val out = distill(
      visit("home", at(7), at(8), "Home"),
      visit("clinic", at(9), at(10), "Clinic", s = Sensitivity.PRIVATE),
      note("n1", at(11), "buy milk"),
      note("n2", at(12), "diagnosis", s = Sensitivity.DEVICE_ONLY),
      usage("u", at(12), at(12, 30), "Therapy", s = Sensitivity.PRIVATE),
      steps("st", at(13), 500),
    )
    assertEquals(listOf("Home", "Clinic"), out.local.today.places)
    assertEquals(listOf("diagnosis", "buy milk"), out.local.recentNotes)
    assertEquals(Sensitivity.DEVICE_ONLY, out.local.maxSensitivity)
    assertNull(out.local.forSync())

    assertEquals(listOf("Home"), out.sync.today.places)
    assertEquals(listOf("buy milk"), out.sync.recentNotes)
    assertTrue(out.sync.today.topApps.isEmpty())
    assertEquals(500, out.sync.today.steps)
    assertEquals(Sensitivity.PERSONAL, out.sync.maxSensitivity)
    assertNotNull(out.sync.forSync())

    // Episodes keep every sensitivity; sync filters them later.
    assertTrue(out.episodes.any { it.sensitivity == Sensitivity.PRIVATE })
  }

  @Test
  fun syncActiveEpisodeComesFromSyncableEventsOnly() {
    val out = distill(calendar("c", at(14, 30), at(15, 30), "Therapy", instanceId = 1, s = Sensitivity.PRIVATE))
    assertEquals("Therapy", out.local.activeEpisode?.title)
    assertNull(out.local.forSync())
    assertNull(out.sync.activeEpisode)
    assertEquals(Sensitivity.PUBLIC, out.sync.maxSensitivity)
    assertNotNull(out.sync.forSync())
  }

  @Test
  fun aPrivateEditHidesTheMeetingFromSyncInsteadOfResurfacingTheOldVersion() {
    val out = distill(
      calendar("v1", at(16), at(17), "Lunch", instanceId = 9, created = at(8)),
      calendar("v2", at(16, 30), at(17), "Doctor", instanceId = 9, created = at(9), s = Sensitivity.PRIVATE),
    )
    assertEquals(NextEvent("Doctor", at(16, 30)), out.local.nextEvent)
    assertNull(out.sync.nextEvent)
  }

  @Test
  fun aRescheduledMeetingIsNotShownAtItsOldTime() {
    val out = distill(
      calendar("v1", at(14), at(16), "Offsite", instanceId = 4, created = at(8)),
      calendar("v2", at(14, days = 7), at(16, days = 7), "Offsite", instanceId = 4, created = at(9)),
    )
    assertTrue(out.episodes.isEmpty())
    assertNull(out.local.activeEpisode)
    assertNull(out.local.nextEvent)
  }

  @Test
  fun syncActiveEpisodeIsAStoredEpisodeThatIsSyncableAsAWhole() {
    val mixed = distill(
      calendar("c", at(14), at(16), "Workshop", instanceId = 1),
      calendar("p", at(14, 30), at(15, 30), "Therapy", instanceId = 2, s = Sensitivity.PRIVATE),
    )
    assertEquals("Therapy", mixed.local.activeEpisode?.title)
    val syncActive = mixed.sync.activeEpisode
    assertEquals("Workshop", syncActive?.title)
    assertTrue(mixed.episodes.any { it.id == syncActive?.id })
    assertNotNull(mixed.sync.forSync())
  }

  @Test
  fun maxSensitivityCountsOnlyInputsThatContributed() {
    val out = distill(
      steps("yesterday", at(10, days = -1), 100, s = Sensitivity.DEVICE_ONLY),
      steps("today", at(10), 100, s = Sensitivity.PUBLIC),
    )
    assertEquals(Sensitivity.PUBLIC, out.local.maxSensitivity)
  }

  @Test
  fun emptyInputGivesAnEmptyPublicSnapshot() {
    val out = distill()
    assertTrue(out.episodes.isEmpty())
    assertEquals(now, out.local.generatedAtMs)
    assertEquals(Sensitivity.PUBLIC, out.local.maxSensitivity)
    assertEquals(out.local, out.sync)
    assertNotNull(out.sync.forSync())
  }

  // -------------------------------------------------------------- malformed

  @Test
  fun malformedPayloadsAreSkipped() {
    val out = distill(
      ev("bad1", EventTypes.HEALTH_STEPS, at(9), at(10), "not json"),
      ev("bad2", EventTypes.HEALTH_STEPS, at(10), at(11), "[1,2]"),
      ev("bad3", EventTypes.HEALTH_STEPS, at(11), at(12), """{"count":"12"}"""),
      ev("bad4", EventTypes.HEALTH_STEPS, at(12), at(13), """{"count":-5}"""),
      steps("ok", at(13), 40),
      ev("u-nolabel", EventTypes.USAGE_SESSION, at(9), at(10), """{"label":5}"""),
      ev("u-open", EventTypes.USAGE_SESSION, at(9), null, """{"label":"Open"}"""),
      ev("u-inverted", EventTypes.USAGE_SESSION, at(10), at(9), """{"label":"Inv"}"""),
      ev("v-point", EventTypes.LOCATION_VISIT, at(9), null, """{"label":"Home"}"""),
      ev("a-bad", EventTypes.LOCATION_ACTIVITY, at(9), at(10), """{"activity":null}"""),
      ev("n-bad", EventTypes.KB_NOTE, at(9), null, """{"text":42}"""),
      ev("c-bad", EventTypes.CALENDAR_EVENT, at(9), at(10), "{"),
      ev("hr", EventTypes.HEALTH_HEART_RATE, at(9), at(10), """{"min":50,"max":90,"avg":70,"samples":60}"""),
      ev("unknown", "x.custom", at(9), at(10), "{}"),
    )
    assertTrue(out.episodes.isEmpty())
    assertEquals(40, out.local.today.steps)
    assertTrue(out.local.today.topApps.isEmpty())
    assertTrue(out.local.today.places.isEmpty())
    assertTrue(out.local.recentNotes.isEmpty())
  }

  @Test
  fun calendarFieldsFallBackSafely() {
    val out = distill(
      ev("c", EventTypes.CALENDAR_EVENT, at(16), at(17), """{"title":7,"allDay":"yes"}"""),
    )
    // No instanceId: its own instance. Bad allDay: treated as timed. Bad title: placeholder.
    assertEquals(NextEvent("Event", at(16)), out.local.nextEvent)
  }

  @Test
  fun durationsReadNaturally() {
    val min = 60_000L
    assertEquals("0 min", Distillation.duration(0))
    assertEquals("45 min", Distillation.duration(45 * min))
    assertEquals("1 h", Distillation.duration(60 * min))
    assertEquals("2 h 5 min", Distillation.duration(125 * min))
  }
}
