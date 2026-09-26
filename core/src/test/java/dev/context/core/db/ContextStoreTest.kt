package dev.context.core.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.context.core.ContextContract
import dev.context.core.json.ContextJson
import dev.context.core.model.Episode
import dev.context.core.model.EventTypes
import dev.context.core.model.Sensitivity
import dev.context.core.service.ContextStore
import dev.context.core.testing.testEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ContextStoreTest {
  private lateinit var db: ContextDatabase
  private lateinit var store: ContextStore
  private val rejected = mutableListOf<String>()

  @Before
  fun setUp() {
    db = ContextDatabase.inMemory(ApplicationProvider.getApplicationContext())
    store = ContextStore(db) { e, _ -> rejected += e.id }
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun logEventsIsIdempotentAndSkipsInvalidRows() {
    val events = listOf(testEvent(1), testEvent(2), testEvent(3).copy(sensitivity = 7))
    assertEquals(2, store.logEventsJson(ContextJson.encodeEvents(events)))
    assertEquals(listOf("evt-00003"), rejected)
    // A retried batch inserts nothing new.
    assertEquals(0, store.logEventsJson(ContextJson.encodeEvents(events.take(2))))
    assertEquals(2, db.events().count())
  }

  @Test
  fun logEventsRejectsMalformedJson() {
    assertThrows(IllegalArgumentException::class.java) { store.logEventsJson("{not an array") }
  }

  @Test
  fun queryRangeReturnsOverlappingEventsFilteredByType() {
    val hour = 3_600_000L
    val base = 100 * hour
    db.events().insertAll(
      listOf(
        // Sleep that started before the window and runs into it.
        testEvent(1, type = EventTypes.HEALTH_SLEEP, startMs = base - 6 * hour, endMs = base + hour),
        // Ended before the window: excluded.
        testEvent(2, type = EventTypes.HEALTH_SLEEP, startMs = base - 10 * hour, endMs = base - 8 * hour),
        // Point event inside the window.
        testEvent(3, type = EventTypes.KB_NOTE, startMs = base + 2 * hour),
        // Starts exactly at toMs: excluded (half-open range).
        testEvent(4, type = EventTypes.KB_NOTE, startMs = base + 4 * hour),
        // Beyond the lookback bound: excluded even though it overlaps.
        testEvent(5, type = EventTypes.CALENDAR_EVENT, startMs = base - ContextContract.MAX_EVENT_SPAN_MS - 1, endMs = base + hour),
      )
    )
    val all = ContextJson.decodeEvents(store.queryRangeJson(base, base + 4 * hour, null))
    assertEquals(listOf("evt-00001", "evt-00003"), all.map { it.id })

    val notes = ContextJson.decodeEvents(store.queryRangeJson(base, base + 4 * hour, arrayOf(EventTypes.KB_NOTE)))
    assertEquals(listOf("evt-00003"), notes.map { it.id })

    assertEquals("[]", store.queryRangeJson(base, base, null))
  }

  @Test
  fun queryRangeReplyStaysWithinBudgetAndSignalsTruncation() {
    val bulky = """{"t":"${"x".repeat(9_000)}"}"""
    db.events().insertAll((1..40).map { testEvent(it, payload = bulky) })
    val reply = store.queryRangeJson(0, Long.MAX_VALUE, null)
    assertTrue(reply.length <= ContextContract.MAX_REPLY_CHARS)
    assertTrue(ContextContract.replyMayBeTruncated(reply.length))
    val page = ContextJson.decodeEvents(reply)
    assertTrue(page.size in 1 until 40)
  }

  @Test
  fun episodeQueries() {
    val dao = db.episodes()
    fun ep(id: String, start: Long, end: Long, sensitivity: Int = Sensitivity.PERSONAL) =
      Episode(id, start, end, "work", id, "", "[]", sensitivity)
    dao.upsertAll(listOf(ep("a", 0, 100), ep("b", 50, 200, Sensitivity.PRIVATE), ep("c", 300, 400)))

    assertEquals(listOf("a", "b"), dao.overlapping(60, 150).map { it.id })
    assertEquals(listOf("a"), dao.overlappingUpTo(60, 150, Sensitivity.SYNC_MAX).map { it.id })
    assertEquals("b", dao.activeAt(150)?.id)
    assertNull(dao.activeAt(250))

    dao.upsertAll(listOf(ep("c", 300, 450).copy(title = "renamed")))
    assertEquals("renamed", dao.byId("c")?.title)
    assertEquals(listOf("c", "b", "a"), dao.latest(10).map { it.id })
  }
}
