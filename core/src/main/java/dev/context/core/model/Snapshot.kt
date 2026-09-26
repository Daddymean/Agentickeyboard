package dev.context.core.model

import kotlinx.serialization.Serializable

/** The "current state" the distiller publishes and the keyboard reads (FROZEN CONTRACT). */
@Serializable
data class Snapshot(
  val generatedAtMs: Long,
  val today: Today = Today(),
  val activeEpisode: Episode? = null,
  val nextEvent: NextEvent? = null,
  val recentNotes: List<String> = emptyList(),
) {
  /**
   * Copy safe to leave the device, as far as the contract allows: drops an
   * active episode above [Sensitivity.SYNC_MAX]. `today` and `recentNotes`
   * carry no sensitivity, so the producer must build them from syncable data
   * (see CONTRACT CHANGE REQUEST in the PR).
   */
  fun redactedForSync(): Snapshot =
    if (activeEpisode != null && !Sensitivity.isSyncable(activeEpisode.sensitivity)) {
      copy(activeEpisode = null)
    } else {
      this
    }

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
