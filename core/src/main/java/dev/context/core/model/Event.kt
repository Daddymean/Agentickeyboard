package dev.context.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * One raw observation logged by a collector or the keyboard. Doubles as the
 * Room row and the IPC/JSON wire shape (FROZEN CONTRACT).
 *
 * Indexes: `startMs` serves untyped range scans; `(type, startMs)` serves typed
 * range scans and, via its leading column, plain `type` lookups — a separate
 * single-column `type` index would be redundant write cost.
 */
@Serializable
@Entity(
  tableName = "events",
  indices = [Index(value = ["startMs"]), Index(value = ["type", "startMs"])],
)
data class Event(
  @PrimaryKey val id: String,
  val type: String,
  val startMs: Long,
  val endMs: Long? = null,
  val source: String,
  /** A JSON document serialized as a string (the contract keeps it opaque). */
  val payload: String,
  val sensitivity: Int,
  val createdMs: Long,
)

/** The event types defined by the contract. Unknown types are still accepted. */
object EventTypes {
  const val HEALTH_SLEEP = "health.sleep"
  const val HEALTH_HEART_RATE = "health.heart_rate"
  const val HEALTH_STEPS = "health.steps"
  const val USAGE_SESSION = "usage.session"
  const val LOCATION_VISIT = "location.visit"
  const val LOCATION_ACTIVITY = "location.activity"
  const val CALENDAR_EVENT = "calendar.event"
  const val KB_NOTE = "kb.note"

  val ALL: Set<String> = setOf(
    HEALTH_SLEEP, HEALTH_HEART_RATE, HEALTH_STEPS, USAGE_SESSION,
    LOCATION_VISIT, LOCATION_ACTIVITY, CALENDAR_EVENT, KB_NOTE,
  )
}

/** Sensitivity levels. Only values <= [SYNC_MAX] may ever leave the device. */
object Sensitivity {
  const val PUBLIC = 0
  const val PERSONAL = 1
  const val PRIVATE = 2
  const val DEVICE_ONLY = 3

  const val SYNC_MAX = PERSONAL

  fun isValid(level: Int): Boolean = level in PUBLIC..DEVICE_ONLY
  fun isSyncable(level: Int): Boolean = level in PUBLIC..SYNC_MAX
}
