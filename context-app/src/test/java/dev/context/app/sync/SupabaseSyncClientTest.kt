package dev.context.app.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.context.app.settings.SettingsStore
import dev.context.app.snapshot.SnapshotStore
import dev.context.core.db.ContextDatabase
import dev.context.core.json.ContextJson
import dev.context.core.model.Episode
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import dev.context.core.model.Sensitivity
import dev.context.core.model.Snapshot
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// A plain Application: ContextApp.onCreate would schedule WorkManager jobs.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class SupabaseSyncClientTest {
  @get:Rule val tmp = TemporaryFolder()

  private lateinit var db: ContextDatabase
  private lateinit var settings: SettingsStore
  private lateinit var snapshots: SnapshotStore
  private val transport = FakeTransport()

  private class FakeTransport : SyncTransport {
    val bodies = mutableListOf<JsonObject>()
    val urls = mutableListOf<String>()
    val tokens = mutableListOf<String>()
    var code = 200

    override fun post(url: String, token: String, body: String): HttpResponse {
      urls += url
      tokens += token
      bodies += ContextJson.json.parseToJsonElement(body).jsonObject
      return HttpResponse(code, if (code == 200) "{}" else "nope")
    }

    fun episodeIds() = bodies.flatMap { b -> b.array("episodes").map { it.jsonObject.string("id") } }

    fun deletedIds() = bodies.flatMap { b -> b.array("deletedEpisodeIds").map { it.jsonPrimitive.content } }

    fun notes() = bodies.flatMap { b -> b.array("notes").map { it.jsonObject } }

    fun dailyStates() = bodies.mapNotNull { b -> (b["dailyState"] as? JsonObject) }
  }

  private val utc = ZoneId.of("UTC")
  private val now = 1_800_000_000_000L

  private fun client() = SupabaseSyncClient(db, snapshots, settings, { utc }, transport)

  @Before
  fun setUp() {
    val app = ApplicationProvider.getApplicationContext<Application>()
    db = ContextDatabase.inMemory(app)
    settings = SettingsStore(app)
    settings.supabaseUrl = "https://example.supabase.co/"
    settings.syncToken = "secret"
    snapshots = SnapshotStore(tmp.newFolder("snapshots"))
  }

  @After
  fun tearDown() {
    db.close()
  }

  private fun episode(i: Int, sensitivity: Int = Sensitivity.PERSONAL, updatedMs: Long = 100L + i) = Episode(
    id = "ep-%04d".format(i),
    startMs = i * 1_000L,
    endMs = i * 1_000L + 500,
    kind = "focus",
    title = "Episode $i",
    summary = "summary $i",
    eventIds = "[]",
    sensitivity = sensitivity,
    updatedMs = updatedMs,
  )

  private fun note(i: Int, startMs: Long, sensitivity: Int = Sensitivity.PERSONAL, payload: String? = null) = Event(
    id = "note-%04d".format(i),
    type = EventTypes.KB_NOTE,
    startMs = startMs,
    source = "keyboard",
    payload = payload ?: buildJsonObject { put("text", "note $i") }.toString(),
    sensitivity = sensitivity,
    createdMs = startMs,
  )

  @Test
  fun skipsWithoutTouchingTheNetworkWhenNotConfigured() = runTest {
    settings.syncToken = ""
    db.episodes().upsertAll(listOf(episode(1)))
    val result = client().syncOnce(now)
    assertEquals("not configured", result.skipped)
    assertTrue(transport.bodies.isEmpty())
  }

  @Test
  fun nothingToSendMeansNoRequest() = runTest {
    val result = client().syncOnce(now)
    assertEquals(SyncResult(), result)
    assertTrue(transport.bodies.isEmpty())
  }

  @Test
  fun postsToTheSyncFunctionWithTheBearerToken() = runTest {
    db.episodes().upsertAll(listOf(episode(1)))
    client().syncOnce(now)
    assertEquals(listOf("https://example.supabase.co/functions/v1/sync"), transport.urls)
    assertEquals(listOf("secret"), transport.tokens)
    val body = transport.bodies.single()
    assertEquals(setOf("episodes", "deletedEpisodeIds", "dailyState", "notes"), body.keys)
    assertEquals(JsonNull, body["dailyState"])
    // Exactly the :core wire shape.
    assertEquals(SupabaseSyncClient.encodeEpisode(episode(1)), body.array("episodes").single())
  }

  @Test
  fun pagesThroughEpisodesAndPersistsTheCursor() = runTest {
    // 450 rows sharing one updatedMs exercise the id tiebreak across pages.
    db.episodes().upsertAll((1..450).map { episode(it, updatedMs = 7L) })
    val first = client().syncOnce(now)
    assertEquals(450, first.episodesUpserted)
    assertEquals(3, transport.bodies.size)
    assertEquals((1..450).map { "ep-%04d".format(it) }, transport.episodeIds())
    assertEquals(7L, settings.getLong(SupabaseSyncClient.KEY_EPISODES_SINCE, 0))
    assertEquals("ep-0450", settings.getString(SupabaseSyncClient.KEY_EPISODES_AFTER))

    // A second pass sends only what changed since.
    transport.bodies.clear()
    db.episodes().upsertChanged(listOf(episode(3).copy(title = "renamed")), nowMs = 50L)
    val second = client().syncOnce(now)
    assertEquals(1, second.episodesUpserted)
    assertEquals(listOf("ep-0003"), transport.episodeIds())
  }

  @Test
  fun episodesAboveTheSyncCeilingAreDeletedNeverUploaded() = runTest {
    db.episodes().upsertAll(
      listOf(
        episode(1, Sensitivity.PUBLIC),
        episode(2, Sensitivity.PRIVATE),
        episode(3, Sensitivity.PERSONAL),
        episode(4, Sensitivity.DEVICE_ONLY),
      )
    )
    val result = client().syncOnce(now)
    assertEquals(2, result.episodesUpserted)
    assertEquals(2, result.episodesDeleted)
    assertEquals(listOf("ep-0001", "ep-0003"), transport.episodeIds())
    assertEquals(listOf("ep-0002", "ep-0004"), transport.deletedIds())
    transport.bodies.flatMap { it.array("episodes") }.forEach {
      assertTrue(Sensitivity.isSyncable(it.jsonObject.getValue("sensitivity").jsonPrimitive.long.toInt()))
    }
  }

  @Test
  fun aFailedRequestThrowsAndLeavesEveryCursorAlone() = runTest {
    db.episodes().upsertAll(listOf(episode(1)))
    db.events().insertAll(listOf(note(1, startMs = 500)))
    snapshots.publish(Snapshot(generatedAtMs = 9), Snapshot(generatedAtMs = 9, maxSensitivity = Sensitivity.PERSONAL))
    for (code in listOf(400, 401, 500)) {
      transport.code = code
      try {
        client().syncOnce(now)
        fail("expected SyncHttpException for $code")
      } catch (e: SyncHttpException) {
        assertEquals(code, e.code)
        assertFalse(e.message.orEmpty().contains("secret"))
      }
    }
    assertEquals(0L, settings.getLong(SupabaseSyncClient.KEY_EPISODES_SINCE, 0))
    assertEquals(null, settings.getString(SupabaseSyncClient.KEY_EPISODES_AFTER))
    assertEquals(0L, settings.getLong(SupabaseSyncClient.KEY_NOTES_SINCE, 0))
    assertEquals(-1L, settings.getLong(SupabaseSyncClient.KEY_DAILY_GENERATED_AT, -1))

    // Once the server recovers, everything goes up.
    transport.code = 200
    transport.bodies.clear()
    val result = client().syncOnce(now)
    assertEquals(1, result.episodesUpserted)
    assertEquals(1, result.notes)
    assertTrue(result.dailyState)
  }

  @Test
  fun syncsOnlySyncableWellFormedNotesAndKeepsACursor() = runTest {
    db.events().insertAll(
      listOf(
        note(1, startMs = 1_000),
        note(2, startMs = 2_000, sensitivity = Sensitivity.PRIVATE),
        note(3, startMs = 3_000, payload = "{not json"),
        note(4, startMs = 4_000, payload = """{"text":42}"""),
        note(5, startMs = 5_000, sensitivity = Sensitivity.PUBLIC),
        note(6, startMs = now + 1), // not started yet
      )
    )
    val result = client().syncOnce(now)
    assertEquals(2, result.notes)
    val notes = transport.notes()
    assertEquals(listOf("note-0001", "note-0005"), notes.map { it.string("id") })
    assertEquals("note 1", notes[0].string("text"))
    assertEquals("keyboard", notes[0].string("source"))
    assertEquals("1970-01-01T00:00:01Z", notes[0].string("createdAt"))
    assertFalse(transport.bodies.toString().contains("note 2"))
    assertEquals(5_000L, settings.getLong(SupabaseSyncClient.KEY_NOTES_SINCE, 0))

    // Nothing new: no request, even though the cursor is inclusive.
    transport.bodies.clear()
    assertEquals(0, client().syncOnce(now).notes)
    assertTrue(transport.bodies.isEmpty())

    // A later note with the same start as the cursor is still picked up.
    db.events().insertAll(listOf(note(7, startMs = 5_000)))
    assertEquals(1, client().syncOnce(now).notes)
    assertEquals(listOf("note-0007"), transport.notes().map { it.string("id") })
  }

  @Test
  fun moreThanAPageOfNotesInOneMillisecondAreAllSent() = runTest {
    db.events().insertAll((1..250).map { note(it, startMs = 1_000) })
    assertEquals(250, client().syncOnce(now).notes)
    assertEquals(250, transport.notes().map { it.string("id") }.toSet().size)
    assertEquals("note-0250", settings.getString(SupabaseSyncClient.KEY_NOTES_AFTER))
  }

  @Test
  fun dailyStateIsSentOncePerGeneration() = runTest {
    val generatedAt = 1_700_000_000_000L // 2023-11-14T22:13:20Z
    val state = Snapshot(generatedAtMs = generatedAt, recentNotes = listOf("hi"), maxSensitivity = Sensitivity.PERSONAL)
    snapshots.publish(Snapshot(generatedAtMs = generatedAt), state)

    assertTrue(client().syncOnce(now).dailyState)
    val daily = transport.dailyStates().single()
    assertEquals("2023-11-14", daily.string("date"))
    assertEquals(ContextJson.json.encodeToJsonElement(Snapshot.serializer(), state), daily["state"])

    transport.bodies.clear()
    assertFalse(client().syncOnce(now).dailyState)
    assertTrue(transport.bodies.isEmpty())

    snapshots.publish(Snapshot(generatedAtMs = generatedAt + 1), state.copy(generatedAtMs = generatedAt + 1))
    assertTrue(client().syncOnce(now).dailyState)
    assertEquals(1, transport.dailyStates().size)
  }

  @Test
  fun dailyStateUsesTheLocalDate() = runTest {
    val generatedAt = 1_700_000_000_000L // 2023-11-15 07:13 in Tokyo
    snapshots.publish(
      Snapshot(generatedAtMs = generatedAt),
      Snapshot(generatedAtMs = generatedAt, maxSensitivity = Sensitivity.PUBLIC),
    )
    SupabaseSyncClient(db, snapshots, settings, { ZoneId.of("Asia/Tokyo") }, transport).syncOnce(now)
    assertEquals("2023-11-15", transport.dailyStates().single().string("date"))
  }

  @Test
  fun aNonSyncableSnapshotIsNeverSent() = runTest {
    snapshots.publish(Snapshot(generatedAtMs = 5), Snapshot(generatedAtMs = 5, maxSensitivity = Sensitivity.PRIVATE))
    assertFalse(client().syncOnce(now).dailyState)
    assertTrue(transport.bodies.isEmpty())
  }

  @Test
  fun requestsRespectTheItemAndSizeCaps() {
    val counts = SupabaseSyncClient.splitForRequests((1..1_234).toList()) { 1 }.map { it.size }
    assertEquals(listOf(500, 500, 234), counts)

    val bySize = SupabaseSyncClient.splitForRequests((1..10).toList()) { SupabaseSyncClient.MAX_ITEM_BYTES / 3 }
    assertEquals(listOf(3, 3, 3, 1), bySize.map { it.size })
    assertEquals((1..10).toList(), bySize.flatten())

    // An oversized item still goes, alone, rather than stalling the cursor.
    val huge = SupabaseSyncClient.splitForRequests(listOf(1, 2)) { SupabaseSyncClient.MAX_ITEM_BYTES * 2 }
    assertEquals(listOf(listOf(1), listOf(2)), huge)
  }

  @Test
  fun everyRequestStaysUnderTheEpisodeCap() = runTest {
    db.episodes().upsertAll((1..1_100).map { episode(it) })
    assertEquals(1_100, client().syncOnce(now).episodesUpserted)
    transport.bodies.forEach { assertTrue(it.array("episodes").size <= SupabaseSyncClient.MAX_ITEMS) }
    assertNotNull(settings.getString(SupabaseSyncClient.KEY_EPISODES_AFTER))
    assertEquals(1_100, transport.episodeIds().toSet().size)
  }
}

private fun JsonObject.array(key: String): JsonArray = getValue(key).jsonArray

private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
