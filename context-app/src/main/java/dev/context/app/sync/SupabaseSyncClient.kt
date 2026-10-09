package dev.context.app.sync

import dev.context.app.settings.SettingsStore
import dev.context.app.snapshot.SnapshotStore
import dev.context.core.db.ContextDatabase
import java.time.ZoneId

/** Scaffold stub: uploads nothing. */
class SupabaseSyncClient(
  private val db: ContextDatabase,
  private val snapshots: SnapshotStore,
  private val settings: SettingsStore,
  private val zone: () -> ZoneId,
) : SyncClient {
  override suspend fun syncOnce(nowMs: Long): SyncResult = SyncResult(skipped = "sync client not implemented yet")
}
