package dev.context.app.distill

import dev.context.core.db.ContextDatabase
import dev.context.core.model.Sensitivity
import dev.context.core.model.Snapshot
import java.time.ZoneId

/** Scaffold stub: no episodes yet, an empty local snapshot and an empty (public) sync snapshot. */
class EpisodeDistiller(
  private val db: ContextDatabase,
  private val zone: () -> ZoneId,
) : Distiller {
  override fun distill(nowMs: Long): DistillOutput = DistillOutput(
    episodes = emptyList(),
    local = Snapshot.empty(nowMs),
    sync = Snapshot(generatedAtMs = nowMs, maxSensitivity = Sensitivity.PUBLIC),
  )
}
