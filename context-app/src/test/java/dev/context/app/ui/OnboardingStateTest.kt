package dev.context.app.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.context.app.collect.Collector
import dev.context.app.settings.SettingsStore
import dev.context.app.snapshot.SnapshotStore
import dev.context.app.work.SyncWorker
import dev.context.core.model.Event
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class OnboardingStateTest {
  @get:Rule val tmp = TemporaryFolder()

  private class FakeCollector(override val key: String, private val missing: () -> List<String>) : Collector {
    override suspend fun missingPermissions(): List<String> = missing()

    override suspend fun collect(fromMs: Long, toMs: Long): List<Event> = emptyList()
  }

  @Test
  fun loadsSourcesStatusesAndSyncSettings() = runTest {
    val settings = SettingsStore(ApplicationProvider.getApplicationContext())
    val now = 1_000_000_000L
    settings.putString(SettingsStore.statusKey("calendar"), "ok: 3 events")
    settings.putLong(SettingsStore.statusAtKey("calendar"), now - 2 * 60_000)
    settings.putString(SettingsStore.statusKey(SyncWorker.STATUS), "uploaded")
    settings.putLong(SettingsStore.statusAtKey(SyncWorker.STATUS), now)
    settings.supabaseUrl = "https://abcd.supabase.co/"
    settings.syncToken = "secret"

    val collectors = listOf(
      FakeCollector("calendar") { emptyList() },
      FakeCollector("usage") { listOf("android.settings.USAGE_ACCESS_SETTINGS") },
      FakeCollector("broken") { error("boom") },
    )
    val state = loadScreenState(collectors, settings, SnapshotStore(tmp.root), now)

    val (calendar, usage, broken) = state.sources
    assertTrue(calendar.ready)
    assertEquals("ok: 3 events · 2 min ago", calendar.status)
    assertEquals(GrantStep.OpenSettings("android.settings.USAGE_ACCESS_SETTINGS"), usage.grantStep)
    assertEquals("never run", usage.status)
    assertFalse(broken.ready)
    assertEquals(listOf("permission check failed: IllegalStateException"), broken.missing)

    assertEquals("https://abcd.supabase.co", state.supabaseUrl)
    assertTrue(state.syncConfigured)
    assertEquals("uploaded · just now", state.syncStatus)
    assertTrue(state.snapshotJson.startsWith("{\n"))
  }
}
