package dev.context.app.sync

import dev.context.app.settings.SettingsStore
import dev.context.app.snapshot.SnapshotStore
import dev.context.core.db.ContextDatabase
import dev.context.core.json.ContextJson
import dev.context.core.model.Episode
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import dev.context.core.model.Sensitivity
import dev.context.core.model.Snapshot
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pushes the syncable slice to the `sync` Edge Function
 * (`POST {supabaseUrl}/functions/v1/sync`). One pass:
 *
 * 1. **Episodes** changed since the `(updatedMs, id)` cursor, a page at a time.
 *    Syncable rows are upserted; the ids of the rest are sent as deletes, so an
 *    episode that became private leaves the cloud. A non-syncable episode body
 *    is never sent.
 * 2. **Notes**: syncable `kb.note` events started since the notes cursor. The
 *    server inserts them with on-conflict-do-nothing, so resending is safe.
 * 3. **Daily state**: [SnapshotStore.sync] (already gated by
 *    [Snapshot.forSync]), sent once per `generatedAtMs`.
 *
 * Each request stays within the server's limits ([MAX_ITEMS] items, ~1 MB).
 * A cursor only moves after the request carrying its rows got a 2xx, and any
 * failure throws (non-2xx as [SyncHttpException]) so the worker retries.
 */
class SupabaseSyncClient internal constructor(
  private val db: ContextDatabase,
  private val snapshots: SnapshotStore,
  private val settings: SettingsStore,
  private val zone: () -> ZoneId,
  private val transport: SyncTransport,
) : SyncClient {
  constructor(
    db: ContextDatabase,
    snapshots: SnapshotStore,
    settings: SettingsStore,
    zone: () -> ZoneId,
  ) : this(db, snapshots, settings, zone, HttpUrlConnectionTransport)

  override suspend fun syncOnce(nowMs: Long): SyncResult {
    if (!settings.isSyncConfigured) return SyncResult(skipped = "not configured")
    return withContext(Dispatchers.IO) {
      val url = settings.supabaseUrl + PATH
      val token = settings.syncToken
      val post = { body: String -> send(url, token, body) }
      val (upserted, deleted) = syncEpisodes(post)
      val notes = syncNotes(nowMs, post)
      val daily = syncDailyState(post)
      SyncResult(episodesUpserted = upserted, episodesDeleted = deleted, notes = notes, dailyState = daily)
    }
  }

  private fun send(url: String, token: String, body: String) {
    val response = transport.post(url, token, body)
    if (response.code !in 200..299) throw SyncHttpException(response.code, response.body)
  }

  private fun syncEpisodes(post: (String) -> Unit): Pair<Int, Int> {
    var since = settings.getLong(KEY_EPISODES_SINCE, 0L)
    var after = settings.getString(KEY_EPISODES_AFTER).orEmpty()
    var upserted = 0
    var deleted = 0
    while (true) {
      val page = db.episodes().changedSince(since, after, PAGE_SIZE)
      if (page.isEmpty()) break
      // Encode syncable rows once; for the rest only the id may leave the device.
      val items = page.map { e ->
        if (Sensitivity.isSyncable(e.sensitivity)) EpisodeItem(e, encodeEpisode(e)) else EpisodeItem(e, null)
      }
      for (chunk in splitForRequests(items) { it.body?.let { b -> sizeOf(b) } ?: (sizeOf(it.episode.id) + 2) }) {
        val ups = chunk.mapNotNull { it.body }
        val dels = chunk.filter { it.body == null }.map { it.episode.id }
        post(requestBody(episodes = ups, deletedIds = dels))
        val last = chunk.last().episode
        since = last.updatedMs
        after = last.id
        settings.putLong(KEY_EPISODES_SINCE, since)
        settings.putString(KEY_EPISODES_AFTER, after)
        upserted += ups.size
        deleted += dels.size
      }
      if (page.size < PAGE_SIZE) break
    }
    return upserted to deleted
  }

  private fun syncNotes(nowMs: Long, post: (String) -> Unit): Int {
    var since = settings.getLong(KEY_NOTES_SINCE, 0L)
    // Ids already sent at exactly `since`: the query re-reads them (the cursor
    // is inclusive so later notes with the same start are not missed).
    var afterId = settings.getString(KEY_NOTES_AFTER).orEmpty()
    var sent = 0
    var limit = PAGE_SIZE
    while (true) {
      val page = db.events().startedInOfTypes(since, nowMs, NOTE_TYPES, limit)
      val fresh = page.filter { it.startMs > since || it.id > afterId }
      if (fresh.isEmpty()) {
        if (page.size < limit) break
        // A full page of already-sent notes sharing one start (the query has no
        // id cursor): read further rather than skip the rest of that millisecond.
        limit *= 2
        continue
      }
      val full = page.size == limit
      limit = PAGE_SIZE
      val items = fresh.map { NoteItem(it, encodeNote(it)) }
      for (chunk in splitForRequests(items) { it.body?.let { b -> sizeOf(b) } ?: 0 }) {
        val notes = chunk.mapNotNull { it.body }
        // Private and malformed notes only move the cursor; no request needed.
        if (notes.isNotEmpty()) post(requestBody(notes = notes))
        val last = chunk.last().event
        since = last.startMs
        afterId = last.id
        settings.putLong(KEY_NOTES_SINCE, since)
        settings.putString(KEY_NOTES_AFTER, afterId)
        sent += notes.size
      }
      if (!full) break
    }
    return sent
  }

  private fun syncDailyState(post: (String) -> Unit): Boolean {
    val state = snapshots.sync()?.forSync() ?: return false
    if (state.generatedAtMs == settings.getLong(KEY_DAILY_GENERATED_AT, NONE)) return false
    val date = Instant.ofEpochMilli(state.generatedAtMs).atZone(zone()).toLocalDate()
    val daily = buildJsonObject {
      put("date", date.toString())
      put("state", ContextJson.json.encodeToJsonElement(Snapshot.serializer(), state))
    }
    post(requestBody(dailyState = daily))
    settings.putLong(KEY_DAILY_GENERATED_AT, state.generatedAtMs)
    return true
  }

  private class EpisodeItem(val episode: Episode, val body: JsonElement?)

  private class NoteItem(val event: Event, val body: JsonObject?)

  internal companion object {
    const val PATH = "/functions/v1/sync"
    const val PAGE_SIZE = 200

    /** Server cap on episodes per request; applied to every item list. */
    const val MAX_ITEMS = 500

    /** Server cap is 1 MB per body; keep headroom for the envelope. */
    const val MAX_ITEM_BYTES = 900_000

    const val KEY_EPISODES_SINCE = "sync.episodes.since"
    const val KEY_EPISODES_AFTER = "sync.episodes.after"
    const val KEY_NOTES_SINCE = "sync.notes.since"
    const val KEY_NOTES_AFTER = "sync.notes.after"
    const val KEY_DAILY_GENERATED_AT = "sync.daily.generatedAt"

    private const val NONE = Long.MIN_VALUE
    private val NOTE_TYPES = listOf(EventTypes.KB_NOTE)

    fun encodeEpisode(episode: Episode): JsonElement =
      ContextJson.json.encodeToJsonElement(Episode.serializer(), episode)

    /** The wire note for a syncable `kb.note`, or null for private or malformed ones. */
    fun encodeNote(event: Event): JsonObject? {
      if (!Sensitivity.isSyncable(event.sensitivity)) return null
      val text = runCatching {
        val payload = ContextJson.json.parseToJsonElement(event.payload) as? JsonObject
        (payload?.get("text") as? JsonPrimitive)?.takeIf { it.isString }?.content
      }.getOrNull() ?: return null
      return buildJsonObject {
        put("id", event.id)
        put("createdAt", Instant.ofEpochMilli(event.startMs).toString())
        put("text", text)
        put("source", event.source)
      }
    }

    fun requestBody(
      episodes: List<JsonElement> = emptyList(),
      deletedIds: List<String> = emptyList(),
      dailyState: JsonObject? = null,
      notes: List<JsonObject> = emptyList(),
    ): String = buildJsonObject {
      put("episodes", JsonArray(episodes))
      put("deletedEpisodeIds", JsonArray(deletedIds.map { JsonPrimitive(it) }))
      put("dailyState", dailyState ?: JsonNull)
      put("notes", JsonArray(notes))
    }.toString()

    fun sizeOf(element: JsonElement): Int = sizeOf(element.toString())

    fun sizeOf(text: String): Int = text.toByteArray(Charsets.UTF_8).size + 1

    /**
     * Splits [items] in order into request-sized chunks: at most [MAX_ITEMS]
     * each and at most [MAX_ITEM_BYTES] by [size]. An item larger than the
     * byte budget goes alone (and the server may reject it).
     */
    fun <T> splitForRequests(items: List<T>, size: (T) -> Int): List<List<T>> {
      val chunks = mutableListOf<List<T>>()
      var current = mutableListOf<T>()
      var bytes = 0
      for (item in items) {
        val itemBytes = size(item)
        if (current.isNotEmpty() && (current.size >= MAX_ITEMS || bytes + itemBytes > MAX_ITEM_BYTES)) {
          chunks += current
          current = mutableListOf()
          bytes = 0
        }
        current += item
        bytes += itemBytes
      }
      if (current.isNotEmpty()) chunks += current
      return chunks
    }
  }
}
