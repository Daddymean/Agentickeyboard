package dev.context.core.json

import dev.context.core.model.Episode
import dev.context.core.model.EventValidator
import dev.context.core.model.NextEvent
import dev.context.core.model.Sensitivity
import dev.context.core.model.Snapshot
import dev.context.core.model.Today
import dev.context.core.testing.testEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextJsonTest {
  private val episode = Episode(
    id = "ep-1", startMs = 10, endMs = 20, kind = "work", title = "Deep work",
    summary = "Editor + docs", eventIds = """["a","b"]""", sensitivity = Sensitivity.PRIVATE,
  )

  @Test
  fun eventsRoundTrip() {
    val events = listOf(testEvent(1), testEvent(2, endMs = 5_000))
    assertEquals(events, ContextJson.decodeEvents(ContextJson.encodeEvents(events)))
  }

  @Test
  fun eventEncodingKeepsEveryContractKeyIncludingNulls() {
    val obj = Json.parseToJsonElement(ContextJson.encodeEvent(testEvent(1))).jsonObject
    assertEquals(
      setOf("id", "type", "startMs", "endMs", "source", "payload", "sensitivity", "createdMs"),
      obj.keys,
    )
    assertEquals(JsonNull, obj["endMs"])
  }

  @Test
  fun snapshotRoundTrip() {
    val snapshot = Snapshot(
      generatedAtMs = 42,
      today = Today(sleepHours = 7.5f, steps = 1234, topApps = listOf("Maps"), places = listOf("Home")),
      activeEpisode = episode,
      nextEvent = NextEvent("Standup", 99),
      recentNotes = listOf("buy milk"),
    )
    assertEquals(snapshot, ContextJson.decodeSnapshot(ContextJson.encodeSnapshot(snapshot)))
  }

  @Test
  fun decodesContractShapedSnapshotAndIgnoresUnknownKeys() {
    val text = """
      {"generatedAtMs": 1700000000000,
       "today": {"sleepHours": null, "steps": 800, "topApps": [], "places": ["Gym"]},
       "activeEpisode": null,
       "nextEvent": {"title": "Dentist", "startMs": 1700000100000},
       "recentNotes": ["a"],
       "addedInV2": {"x": 1}}
    """.trimIndent()
    val snapshot = ContextJson.decodeSnapshot(text)
    assertNull(snapshot.today.sleepHours)
    assertEquals(800, snapshot.today.steps)
    assertEquals("Dentist", snapshot.nextEvent?.title)
  }

  @Test
  fun emptySnapshotEncodesAllKeys() {
    val obj = Json.parseToJsonElement(ContextJson.encodeSnapshot(Snapshot.empty())).jsonObject
    assertEquals(setOf("generatedAtMs", "today", "activeEpisode", "nextEvent", "recentNotes"), obj.keys)
  }

  @Test
  fun redactedForSyncDropsPrivateActiveEpisodeOnly() {
    val sensitive = Snapshot(generatedAtMs = 1, activeEpisode = episode)
    assertNull(sensitive.redactedForSync().activeEpisode)
    val personal = sensitive.copy(activeEpisode = episode.copy(sensitivity = Sensitivity.PERSONAL))
    assertNotNull(personal.redactedForSync().activeEpisode)
  }

  @Test
  fun joinArrayMatchesArrayLength() {
    val items = listOf(testEvent(1), testEvent(2)).map(ContextJson::encodeEvent)
    val joined = ContextJson.joinArray(items)
    assertEquals(ContextJson.arrayLength(items.sumOf { it.length }, items.size), joined.length)
    assertEquals(2, ContextJson.decodeEvents(joined).size)
    assertEquals("[]", ContextJson.joinArray(emptyList()))
  }

  @Test
  fun validatorAcceptsGoodAndRejectsBadEvents() {
    assertNull(EventValidator.validate(testEvent(1)))
    assertNotNull(EventValidator.validate(testEvent(1).copy(id = " ")))
    assertNotNull(EventValidator.validate(testEvent(1).copy(sensitivity = 4)))
    assertNotNull(EventValidator.validate(testEvent(1, startMs = 100, endMs = 50)))
    assertNotNull(EventValidator.validate(testEvent(1, payload = "not json")))
    val huge = testEvent(1, payload = """{"t":"${"x".repeat(20_000)}"}""")
    assertTrue(EventValidator.validate(huge)!!.contains("encoded event"))
  }
}
