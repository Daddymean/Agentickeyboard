package dev.context.app.distill

import dev.context.app.snapshot.SnapshotStore
import dev.context.core.db.ContextDatabase

/** Applies one [Distiller] run: stamps changed episodes and publishes both snapshots. */
class DistillRunner(
  private val db: ContextDatabase,
  private val snapshots: SnapshotStore,
  private val distiller: Distiller,
) {
  /** @return how many episodes were written (new or changed). */
  fun run(nowMs: Long): Int {
    val output = distiller.distill(nowMs)
    val written = db.episodes().upsertChanged(output.episodes, nowMs)
    snapshots.publish(output.local, output.sync)
    return written
  }
}
