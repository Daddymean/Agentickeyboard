package dev.context.app.sync

/** Outcome of one sync pass; [skipped] explains a pass that did nothing on purpose. */
data class SyncResult(
  val episodesUpserted: Int = 0,
  val episodesDeleted: Int = 0,
  val notes: Int = 0,
  val dailyState: Boolean = false,
  val skipped: String? = null,
) {
  val summary: String
    get() = skipped?.let { "skipped: $it" }
      ?: "ok: $episodesUpserted episodes, $episodesDeleted deleted, $notes notes" +
        if (dailyState) ", daily state" else ""
}

/**
 * Pushes the syncable slice (sensitivity <= 1) to Supabase. Throws on transport
 * or server errors so the worker can retry; cursors only advance after success.
 */
interface SyncClient {
  suspend fun syncOnce(nowMs: Long): SyncResult
}
