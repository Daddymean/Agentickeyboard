package dev.context.core.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.context.core.model.Episode

/**
 * Blocking DAO for distilled episodes; see [EventDao] for threading notes.
 *
 * An abstract class rather than an interface so [upsertChanged] can run its
 * read-compare-write as one transaction.
 */
@Dao
abstract class EpisodeDao {
  /**
   * Raw replace-by-id. Writes `updatedMs` exactly as given, so sync will not
   * see the change unless the caller stamped it. The distiller should use
   * [upsertChanged] instead.
   */
  @Upsert
  abstract fun upsertAll(episodes: List<Episode>)

  /**
   * The distiller's write path. Inserts new episodes and replaces changed ones,
   * stamping both with `updatedMs = nowMs`. Episodes identical to the stored row
   * (ignoring `updatedMs`) are skipped, so re-deriving a day does not make sync
   * re-upload it.
   *
   * @return how many episodes were written.
   */
  @Transaction
  open fun upsertChanged(episodes: List<Episode>, nowMs: Long): Int {
    if (episodes.isEmpty()) return 0
    val existing = HashMap<String, Episode>(episodes.size)
    // Stay under SQLite's bound-variable limit.
    episodes.map { it.id }.distinct().chunked(500).forEach { ids ->
      byIds(ids).associateByTo(existing) { it.id }
    }
    val changed = episodes
      .filter { e -> existing[e.id]?.copy(updatedMs = e.updatedMs) != e }
      .map { it.copy(updatedMs = nowMs) }
    if (changed.isNotEmpty()) upsertAll(changed)
    return changed.size
  }

  @Query("SELECT * FROM episodes WHERE id = :id")
  abstract fun byId(id: String): Episode?

  @Query("SELECT * FROM episodes WHERE id IN (:ids)")
  abstract fun byIds(ids: List<String>): List<Episode>

  /**
   * Sync cursor: episodes written after `(sinceMs, afterId)`, ordered by
   * `(updatedMs, id)`. Pass the last row's `updatedMs` and `id` to get the
   * next page; start from `(0, "")`. The id tiebreak matters because one
   * [upsertChanged] call stamps a whole batch with the same `updatedMs`.
   *
   * Returns rows of **every** sensitivity on purpose: an episode whose
   * sensitivity rose above [dev.context.core.model.Sensitivity.SYNC_MAX] after
   * it was uploaded must be deleted remotely, and sync can only see that here.
   * Upload rows that are syncable and delete the remote copy of the rest.
   */
  @Query(
    """
    SELECT * FROM episodes
    WHERE updatedMs > :sinceMs OR (updatedMs = :sinceMs AND id > :afterId)
    ORDER BY updatedMs, id LIMIT :limit
    """
  )
  abstract fun changedSince(sinceMs: Long, afterId: String, limit: Int): List<Episode>

  /** Episodes overlapping `[fromMs, toMs)`, oldest first. */
  @Query(
    """
    SELECT * FROM episodes
    WHERE startMs < :toMs AND endMs > :fromMs
    ORDER BY startMs, id
    """
  )
  abstract fun overlapping(fromMs: Long, toMs: Long): List<Episode>

  /** Syncable subset for the uploader: never returns rows above [maxSensitivity]. */
  @Query(
    """
    SELECT * FROM episodes
    WHERE startMs < :toMs AND endMs > :fromMs AND sensitivity <= :maxSensitivity
    ORDER BY startMs, id
    """
  )
  abstract fun overlappingUpTo(fromMs: Long, toMs: Long, maxSensitivity: Int): List<Episode>

  @Query(
    """
    SELECT * FROM episodes
    WHERE kind = :kind AND startMs >= :fromMs AND startMs < :toMs
    ORDER BY startMs, id
    """
  )
  abstract fun ofKind(kind: String, fromMs: Long, toMs: Long): List<Episode>

  /** The most recently started episode that covers [nowMs], if any. */
  @Query(
    """
    SELECT * FROM episodes
    WHERE startMs <= :nowMs AND endMs > :nowMs
    ORDER BY startMs DESC LIMIT 1
    """
  )
  abstract fun activeAt(nowMs: Long): Episode?

  @Query("SELECT * FROM episodes ORDER BY startMs DESC LIMIT :limit")
  abstract fun latest(limit: Int): List<Episode>

  /**
   * Hard delete. Invisible to [changedSince], so remote copies of synced
   * episodes are left behind; prefer re-deriving in place.
   */
  @Query("DELETE FROM episodes WHERE id IN (:ids)")
  abstract fun deleteByIds(ids: List<String>): Int

  @Query("DELETE FROM episodes WHERE endMs < :beforeMs")
  abstract fun deleteEndedBefore(beforeMs: Long): Int
}
