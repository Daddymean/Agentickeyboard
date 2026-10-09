package dev.context.app.distill

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.context.app.snapshot.SnapshotStore
import dev.context.core.ContextContract
import dev.context.core.db.ContextDatabase
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import dev.context.core.model.NextEvent
import dev.context.core.model.Sensitivity
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// A plain Application: ContextApp.onCreate would schedule WorkManager jobs.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class EpisodeDistillerTest {
  @get:Rule val tmp = TemporaryFolder()

  private val zone = ZoneId.of("Europe/Berlin")
  private val now = ZonedDateTime.of(2026, 10, 9, 15, 0, 0, 0, zone).toInstant().toEpochMilli()
  private val minute = 60_000L
  private val hour = 60 * minute

  private lateinit var db: ContextDatabase

  @Before
  fun setUp() {
    db = ContextDatabase.inMemory(ApplicationProvider.getApplicationContext<Application>())
  }

  @After
  fun tearDown() = db.close()

  private fun ev(id: String, type: String, start: Long, end: Long?, payload: String, created: Long = start) = Event(
    id = id, type = type, startMs = start, endMs = end, source = "test",
    payload = payload, sensitivity = Sensitivity.PERSONAL, createdMs = created,
  )

  private fun distiller() = EpisodeDistiller(db, { zone })

  @Test
  fun loadsEachInputFromItsOwnWindow() {
    db.events().insertAll(
      listOf(
        ev("visit", EventTypes.LOCATION_VISIT, now - 3 * hour, now - 2 * hour, """{"label":"Cafe"}"""),
        // Started before the 48 h window but still running into it.
        ev("long", EventTypes.LOCATION_VISIT, now - 50 * hour, now - 47 * hour, """{"label":"Cabin"}"""),
        ev("ancient", EventTypes.LOCATION_VISIT, now - 60 * hour, now - 59 * hour, """{"label":"Old"}"""),
        ev("meeting", EventTypes.CALENDAR_EVENT, now + 2 * hour, now + 3 * hour,
          """{"instanceId":1,"title":"Review","allDay":false,"calendar":"Work"}""", created = now - 5 * hour),
        ev("note", EventTypes.KB_NOTE, now - 6 * 24 * hour, null, """{"text":"remember"}"""),
        ev("stale-note", EventTypes.KB_NOTE, now - 8 * 24 * hour, null, """{"text":"forgotten"}"""),
      ),
    )
    val out = distiller().distill(now)
    assertEquals(listOf("Cabin", "Cafe"), out.episodes.map { it.title })
    assertEquals(NextEvent("Review", now + 2 * hour), out.local.nextEvent)
    assertEquals(listOf("remember"), out.local.recentNotes)
    assertEquals(listOf("Cafe"), out.local.today.places)
  }

  @Test
  fun pagesPastTheRowLimit() {
    val dayStart = ZonedDateTime.of(2026, 10, 9, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
    val buckets = ContextContract.QUERY_ROW_LIMIT + 300
    val stepMs = (now - dayStart) / buckets
    db.events().insertAll(
      (0 until buckets).map { i ->
        ev("st$i", EventTypes.HEALTH_STEPS, dayStart + i * stepMs, dayStart + (i + 1) * stepMs, """{"count":1}""")
      },
    )
    assertEquals(buckets, distiller().distill(now).local.today.steps)
  }

  @Test
  fun rerunningWithoutNewEventsWritesNothing() {
    db.events().insertAll(
      listOf(
        ev("u1", EventTypes.USAGE_SESSION, now - 2 * hour, now - 2 * hour + 20 * minute,
          """{"package":"p","label":"Docs"}"""),
        ev("s1", EventTypes.HEALTH_SLEEP, now - 15 * hour, now - 8 * hour, """{"durationMin":400}"""),
      ),
    )
    val snapshots = SnapshotStore(tmp.root)
    val runner = DistillRunner(db, snapshots, distiller())
    assertEquals(2, runner.run(now))
    assertEquals(0, runner.run(now + minute))
    assertNotNull(snapshots.sync())
    assertEquals(listOf("Docs"), snapshots.local().today.topApps)
  }
}
