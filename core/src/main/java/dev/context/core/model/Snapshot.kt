package dev.context.core.model

import kotlinx.serialization.Serializable

/**
 * The "current state" the distiller publishes and the keyboard reads (FROZEN
 * CONTRACT, plus the additive `maxSensitivity`).
 *
 * The distiller publishes two of these:
 * - a **local** one from all inputs (served by `getSnapshot` to the keyboard), and
 * - a **sync** one built only from inputs with sensitivity <=
 *   [Sensitivity.SYNC_MAX], with [maxSensitivity] set accordingly.
 *
 * Only the second may be uploaded. [forSync] is the gate.
 */
@Serializable
data class Snapshot(
  val generatedAtMs: Long,
  val today: Today = Today(),
  val activeEpisode: Episode? = null,
  val nextEvent: NextEvent? = null,
  val recentNotes: List<String> = emptyList(),
  /**
   * The highest sensitivity of any input this snapshot was built from, as
   * declared by its producer. Defaults to [Sensitivity.DEVICE_ONLY], so a
   * snapshot that doesn't declare it can never be synced (fail closed).
   */
  val maxSensitivity: Int = Sensitivity.DEVICE_ONLY,
) {
  /** True when this snapshot may leave the device. */
  val isSyncable: Boolean
    get() = Sensitivity.isSyncable(maxSensitivity) &&
      (activeEpisode == null || Sensitivity.isSyncable(activeEpisode.sensitivity))

  /**
   * This snapshot if it may leave the device, otherwise null. Nothing is
   * redacted: `today` and `recentNotes` carry no per-item sensitivity, so a
   * snapshot is either built syncable or not synced at all.
   */
  fun forSync(): Snapshot? = if (isSyncable) this else null

  companion object {
    /** Snapshot for "nothing known yet"; lets callers avoid null checks. */
    fun empty(generatedAtMs: Long = 0L): Snapshot = Snapshot(generatedAtMs = generatedAtMs)
  }
}

@Serializable
data class Today(
  val sleepHours: Float? = null,
  val steps: Int? = null,
  val topApps: List<String> = emptyList(),
  val places: List<String> = emptyList(),
)

@Serializable
data class NextEvent(
  val title: String,
  val startMs: Long,
)
