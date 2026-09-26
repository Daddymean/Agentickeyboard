package dev.context.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.context.core.model.Episode

/** Blocking DAO for distilled episodes; see [EventDao] for threading notes. */
@Dao
interface EpisodeDao {
  /** The distiller re-derives episodes, so writes replace by id. */
  @Upsert
  fun upsertAll(episodes: List<Episode>)

  @Query("SELECT * FROM episodes WHERE id = :id")
  fun byId(id: String): Episode?

  /** Episodes overlapping `[fromMs, toMs)`, oldest first. */
  @Query(
    """
    SELECT * FROM episodes
    WHERE startMs < :toMs AND endMs > :fromMs
    ORDER BY startMs, id
    """
  )
  fun overlapping(fromMs: Long, toMs: Long): List<Episode>

  /** Syncable subset for the uploader: never returns rows above [maxSensitivity]. */
  @Query(
    """
    SELECT * FROM episodes
    WHERE startMs < :toMs AND endMs > :fromMs AND sensitivity <= :maxSensitivity
    ORDER BY startMs, id
    """
  )
  fun overlappingUpTo(fromMs: Long, toMs: Long, maxSensitivity: Int): List<Episode>

  @Query(
    """
    SELECT * FROM episodes
    WHERE kind = :kind AND startMs >= :fromMs AND startMs < :toMs
    ORDER BY startMs, id
    """
  )
  fun ofKind(kind: String, fromMs: Long, toMs: Long): List<Episode>

  /** The most recently started episode that covers [nowMs], if any. */
  @Query(
    """
    SELECT * FROM episodes
    WHERE startMs <= :nowMs AND endMs > :nowMs
    ORDER BY startMs DESC LIMIT 1
    """
  )
  fun activeAt(nowMs: Long): Episode?

  @Query("SELECT * FROM episodes ORDER BY startMs DESC LIMIT :limit")
  fun latest(limit: Int): List<Episode>

  @Query("DELETE FROM episodes WHERE id IN (:ids)")
  fun deleteByIds(ids: List<String>): Int

  @Query("DELETE FROM episodes WHERE endMs < :beforeMs")
  fun deleteEndedBefore(beforeMs: Long): Int
}
