package dev.context.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.context.core.model.Event

/**
 * Blocking DAO: its callers are binder threads and WorkManager workers, both
 * already off the main thread, so suspend wrappers would only add allocation.
 *
 * Range semantics are half-open `[fromMs, toMs)`. "Overlapping" queries also
 * return events that started before `fromMs` (but no earlier than `lookbackFromMs`)
 * and are still running at `fromMs`; point events (`endMs` null) match on start.
 */
@Dao
interface EventDao {
  /**
   * Insert-or-ignore on id so ingest is idempotent and client retries are safe.
   * Returns the new row ids, -1 for each ignored duplicate.
   */
  @Insert(onConflict = OnConflictStrategy.IGNORE)
  fun insertAll(events: List<Event>): List<Long>

  @Query("SELECT * FROM events WHERE id = :id")
  fun byId(id: String): Event?

  @Query("SELECT * FROM events WHERE id IN (:ids)")
  fun byIds(ids: List<String>): List<Event>

  @Query(
    """
    SELECT * FROM events
    WHERE startMs >= :fromMs AND startMs < :toMs
    ORDER BY startMs, id LIMIT :limit
    """
  )
  fun startedIn(fromMs: Long, toMs: Long, limit: Int): List<Event>

  @Query(
    """
    SELECT * FROM events
    WHERE type IN (:types) AND startMs >= :fromMs AND startMs < :toMs
    ORDER BY startMs, id LIMIT :limit
    """
  )
  fun startedInOfTypes(fromMs: Long, toMs: Long, types: List<String>, limit: Int): List<Event>

  @Query(
    """
    SELECT * FROM events
    WHERE startMs >= :lookbackFromMs AND startMs < :toMs
      AND (startMs >= :fromMs OR endMs > :fromMs)
    ORDER BY startMs, id LIMIT :limit
    """
  )
  fun overlapping(fromMs: Long, toMs: Long, lookbackFromMs: Long, limit: Int): List<Event>

  @Query(
    """
    SELECT * FROM events
    WHERE type IN (:types) AND startMs >= :lookbackFromMs AND startMs < :toMs
      AND (startMs >= :fromMs OR endMs > :fromMs)
    ORDER BY startMs, id LIMIT :limit
    """
  )
  fun overlappingOfTypes(
    fromMs: Long,
    toMs: Long,
    lookbackFromMs: Long,
    types: List<String>,
    limit: Int,
  ): List<Event>

  @Query("SELECT * FROM events WHERE type = :type ORDER BY startMs DESC LIMIT :limit")
  fun latestOfType(type: String, limit: Int): List<Event>

  @Query("SELECT COUNT(*) FROM events")
  fun count(): Int

  /** Retention: drops raw events that started before [beforeMs]. Returns rows deleted. */
  @Query("DELETE FROM events WHERE startMs < :beforeMs")
  fun deleteStartedBefore(beforeMs: Long): Int
}
