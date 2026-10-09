package dev.context.app

import android.content.Context
import android.util.Log
import dev.context.app.collect.Collector
import dev.context.app.collect.calendar.CalendarCollector
import dev.context.app.collect.health.HealthCollector
import dev.context.app.collect.location.LocationCollector
import dev.context.app.collect.usage.UsageCollector
import dev.context.app.distill.Distiller
import dev.context.app.distill.EpisodeDistiller
import dev.context.app.settings.SettingsStore
import dev.context.app.snapshot.SnapshotStore
import dev.context.app.sync.SupabaseSyncClient
import dev.context.app.sync.SyncClient
import dev.context.core.db.ContextDatabase
import dev.context.core.service.ContextStore
import java.io.File
import java.time.ZoneId

/**
 * Hand-wired dependency graph (the project uses no DI framework). One instance
 * per process, owned by [ContextApp]; tests build the pieces directly.
 */
class AppGraph(context: Context) {
  private val app = context.applicationContext

  val db: ContextDatabase = ContextDatabase.get(app)

  val store = ContextStore(db) { event, reason -> Log.w(TAG, "rejected event ${event.id}: $reason") }

  val settings = SettingsStore(app)

  val snapshots = SnapshotStore(File(app.noBackupFilesDir, "snapshots"))

  val collectors: List<Collector> by lazy {
    listOf(HealthCollector(app), UsageCollector(app), LocationCollector(app), CalendarCollector(app))
  }

  val distiller: Distiller by lazy { EpisodeDistiller(db, ZoneId::systemDefault) }

  val sync: SyncClient by lazy { SupabaseSyncClient(db, snapshots, settings, ZoneId::systemDefault) }

  private companion object {
    const val TAG = "ContextGraph"
  }
}
