package dev.context.core.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * A distilled span of time built from events (FROZEN CONTRACT). `eventIds` is
 * a JSON array of Event ids serialized as a string.
 *
 * `updatedMs` (added in schema v2) is the sync cursor: the device time of the
 * last write that changed the episode's content. It is set by
 * [dev.context.core.db.EpisodeDao.upsertChanged]; callers never set it by hand.
 */
@Serializable
@Entity(
  tableName = "episodes",
  indices = [
    Index(value = ["startMs"]),
    Index(value = ["endMs"]),
    Index(value = ["kind", "startMs"]),
    Index(value = ["updatedMs"]),
  ],
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
  @ColumnInfo(defaultValue = "0") val updatedMs: Long = 0L,
)
