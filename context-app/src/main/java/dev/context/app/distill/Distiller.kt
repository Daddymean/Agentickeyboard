package dev.context.app.distill

import dev.context.core.model.Episode
import dev.context.core.model.Snapshot

/**
 * What one distill run produces.
 *
 * @property episodes episodes for the recomputed window, with ids stable across
 *   runs so unchanged ones are skipped by `EpisodeDao.upsertChanged`.
 * @property local snapshot from every input, served to the keyboard.
 * @property sync snapshot from inputs with sensitivity <= 1 only, with
 *   `maxSensitivity` set accordingly; the only one that may be uploaded.
 */
data class DistillOutput(
  val episodes: List<Episode>,
  val local: Snapshot,
  val sync: Snapshot,
)

/** Turns stored events into episodes and the two snapshots. Pure with respect to the clock: takes `nowMs`. */
interface Distiller {
  fun distill(nowMs: Long): DistillOutput
}
