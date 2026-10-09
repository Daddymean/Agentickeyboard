package dev.context.app.ui

import dev.context.app.collect.Collector
import dev.context.app.settings.SettingsStore
import dev.context.app.snapshot.SnapshotStore
import dev.context.app.work.SyncWorker
import kotlin.coroutines.cancellation.CancellationException

/** One collector as the screen shows it. */
data class SourceRow(
  val key: String,
  /** What [Collector.missingPermissions] reported; empty means ready. */
  val missing: List<String>,
  /** Last run status with its age, see [formatStatus]. */
  val status: String,
) {
  val ready: Boolean get() = missing.isEmpty()
  val grantStep: GrantStep? get() = nextGrantStep(missing)
}

/** Everything the screen reads from disk, loaded together off the main thread. */
data class ScreenState(
  val sources: List<SourceRow>,
  val supabaseUrl: String,
  val syncToken: String,
  val syncConfigured: Boolean,
  val syncStatus: String,
  /** The local snapshot, pretty-printed. */
  val snapshotJson: String,
)

/**
 * Reads collector readiness, statuses, sync settings and the local snapshot.
 * Blocking and suspending: call it from a background dispatcher. A collector
 * whose permission check throws is shown with the error as its missing item.
 */
suspend fun loadScreenState(
  collectors: List<Collector>,
  settings: SettingsStore,
  snapshots: SnapshotStore,
  nowMs: Long,
): ScreenState {
  fun status(key: String) =
    formatStatus(settings.getString(SettingsStore.statusKey(key)), settings.getLong(SettingsStore.statusAtKey(key), 0L), nowMs)

  val sources = collectors.map { collector ->
    val missing = try {
      collector.missingPermissions()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      listOf("permission check failed: ${e.javaClass.simpleName}")
    }
    SourceRow(collector.key, missing, status(collector.key))
  }
  return ScreenState(
    sources = sources,
    supabaseUrl = settings.supabaseUrl,
    syncToken = settings.syncToken,
    syncConfigured = settings.isSyncConfigured,
    syncStatus = status(SyncWorker.STATUS),
    snapshotJson = prettyJson(snapshots.localJson()),
  )
}
