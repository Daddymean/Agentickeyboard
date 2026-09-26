package dev.context.core.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A distilled span of time built from events (FROZEN CONTRACT). `eventIds` is
 * a JSON array of Event ids serialized as a string.
 */
@Serializable
@Entity(
  tableName = "episodes",
  indices = [Index(value = ["startMs"]), Index(value = ["endMs"]), Index(value = ["kind", "startMs"])],
)
data class Episode(
  @PrimaryKey val id: String,
  val startMs: Long,
  val endMs: Long,
  val kind: String,
  val title: String,
  val summary: String,
  val eventIds: String,
  val sensitivity: Int,
)
