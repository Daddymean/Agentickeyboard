package dev.context.app.collect

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.context.app.settings.SettingsStore
import dev.context.core.db.ContextDatabase
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import dev.context.core.model.Sensitivity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// A plain Application: ContextApp.onCreate would schedule WorkManager jobs.
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CollectRunnerTest {
  private lateinit var db: ContextDatabase
  private lateinit var settings: SettingsStore

  private class FakeCollector(
    override val key: String,
    var missing: List<String> = emptyList(),
    var produce: (Long, Long) -> List<Event> = { _, _ -> emptyList() },
  ) : Collector {
    val windows = mutableListOf<Pair<Long, Long>>()

    override suspend fun missingPermissions() = missing

    override suspend fun collect(fromMs: Long, toMs: Long): List<Event> {
      windows += fromMs to toMs
      return produce(fromMs, toMs)
    }
  }

  private fun event(id: String, startMs: Long, sensitivity: Int = Sensitivity.PERSONAL) = Event(
    id = id, type = EventTypes.USAGE_SESSION, startMs = startMs, endMs = startMs + 1_000,
    source = "test", payload = "{}", sensitivity = sensitivity, createdMs = startMs,
  )

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    db = ContextDatabase.inMemory(context)
    settings = SettingsStore(context)
  }

  @After
  fun tearDown() = db.close()

  @Test
  fun firstRunLooksBackADayThenResumesFromTheCursorWithOverlap() = runTest {
    val collector = FakeCollector("fake", produce = { _, _ -> listOf(event("a", 1)) })
    val runner = CollectRunner(db, settings, listOf(collector))
    val day = CollectRunner.DEFAULT_LOOKBACK_MS
    val now = 10 * day

    runner.runAll(now)
    runner.runAll(now + 3_600_000)

    assertEquals(now - day - collector.overlapMs, collector.windows[0].first)
    assertEquals(now - collector.overlapMs, collector.windows[1].first)
    // The overlapping re-read returned the same id: stored once.
    assertEquals(1, db.events().count())
  }

  @Test
  fun skipsCollectorsMissingPermissionsWithoutMovingTheirCursor() = runTest {
    val collector = FakeCollector("fake", missing = listOf("android.permission.READ_CALENDAR"))
    val outcome = CollectRunner(db, settings, listOf(collector)).runAll(5_000).single()
    assertTrue(outcome.summary.startsWith("skipped"))
    assertTrue(collector.windows.isEmpty())
    assertEquals(-1L, settings.getLong(SettingsStore.cursorKey("fake"), -1L))
  }

  @Test
  fun aFailingCollectorDoesNotStopTheOthers() = runTest {
    val broken = FakeCollector("broken", produce = { _, _ -> error("boom") })
    val fine = FakeCollector("fine", produce = { _, _ -> listOf(event("b", 2)) })
    val outcomes = CollectRunner(db, settings, listOf(broken, fine)).runAll(5_000)
    assertNotNull(outcomes[0].error)
    assertEquals(1, outcomes[1].inserted)
    assertEquals(-1L, settings.getLong(SettingsStore.cursorKey("broken"), -1L))
    assertTrue(settings.getString(SettingsStore.statusKey("broken"))!!.startsWith("error"))
  }

  @Test
  fun invalidEventsAreCountedAndNotStored() = runTest {
    val collector = FakeCollector("fake", produce = { _, _ -> listOf(event("ok", 1), event("bad", 2, sensitivity = 9)) })
    val outcome = CollectRunner(db, settings, listOf(collector)).runAll(5_000).single()
    assertEquals(1, outcome.inserted)
    assertEquals(1, outcome.rejected)
  }
}
