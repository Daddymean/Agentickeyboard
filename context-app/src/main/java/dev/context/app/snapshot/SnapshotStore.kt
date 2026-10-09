package dev.context.app.snapshot

import dev.context.core.json.ContextJson
import dev.context.core.model.Snapshot
import java.io.File
import java.io.IOException

/**
 * Holds the two snapshots the distiller publishes:
 * - **local**: built from every input, served verbatim by `getSnapshot` (kept as
 *   its encoded JSON so the binder call never re-encodes);
 * - **sync**: built from syncable inputs only, read by the sync client.
 *
 * Both live in memory and in files under [dir], so a freshly started process
 * can answer `getSnapshot` before the first distill. Writes are atomic
 * (write-then-rename). A sync snapshot that fails [Snapshot.forSync] is never
 * stored.
 */
class SnapshotStore(private val dir: File) {
  private val lock = Any()
  @Volatile private var local: String? = null
  private var sync: Snapshot? = null
  private var syncLoaded = false

  /** Local snapshot JSON; an empty snapshot until the first [publish]. */
  fun localJson(): String {
    local?.let { return it }
    synchronized(lock) {
      local?.let { return it }
      val loaded = read(LOCAL_FILE)?.takeIf { decodes(it) }
        ?: ContextJson.encodeSnapshot(Snapshot.empty(System.currentTimeMillis()))
      local = loaded
      return loaded
    }
  }

  fun local(): Snapshot = ContextJson.decodeSnapshot(localJson())

  /** The latest syncable snapshot, or null when there is none to upload. */
  fun sync(): Snapshot? = synchronized(lock) {
    if (!syncLoaded) {
      sync = read(SYNC_FILE)
        ?.let { runCatching { ContextJson.decodeSnapshot(it) }.getOrNull() }
        ?.forSync()
      syncLoaded = true
    }
    sync
  }

  /** Replaces both snapshots. [syncSnapshot] is dropped unless it passes [Snapshot.forSync]. */
  @Throws(IOException::class)
  fun publish(localSnapshot: Snapshot, syncSnapshot: Snapshot?) {
    val localEncoded = ContextJson.encodeSnapshot(localSnapshot)
    val gated = syncSnapshot?.forSync()
    synchronized(lock) {
      write(LOCAL_FILE, localEncoded)
      if (gated != null) write(SYNC_FILE, ContextJson.encodeSnapshot(gated)) else File(dir, SYNC_FILE).delete()
      local = localEncoded
      sync = gated
      syncLoaded = true
    }
  }

  private fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()

  private fun write(name: String, text: String) {
    dir.mkdirs()
    val tmp = File(dir, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(File(dir, name))) throw IOException("could not replace ${File(dir, name)}")
  }

  private fun decodes(text: String): Boolean = runCatching { ContextJson.decodeSnapshot(text) }.isSuccess

  private companion object {
    const val LOCAL_FILE = "local.json"
    const val SYNC_FILE = "sync.json"
  }
}
