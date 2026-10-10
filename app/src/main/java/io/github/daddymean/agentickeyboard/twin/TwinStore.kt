package io.github.daddymean.agentickeyboard.twin

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.io.File

/** One decrypted twin entry, for the viewer and for style stats. */
data class TwinEntry(val id: Long, val atMillis: Long, val text: String, val packageName: String?)

/**
 * KEYBOARD-024: the legacy twin's on-device store.
 *
 * Each row is `(id, created_at, iv, body)`. `body` is AES-GCM ciphertext of a small
 * JSON object holding the text and the app package; the timestamp is the only
 * clear-text column (for ordering and pruning) and is bound into the GCM tag as
 * associated data, so a row cannot be re-dated without failing to open.
 *
 * The file lives in `noBackupFilesDir`, which Android never backs up, and the app's
 * backup and device-transfer rules exclude every domain as well. `secure_delete`
 * overwrites deleted rows. [deleteAll] deletes the database files and destroys the
 * Keystore key.
 *
 * Bounded: at most [maxEntries] rows and [maxBytes] of ciphertext; past either,
 * the oldest rows go first. Every method does disk or Keystore work, so it must
 * be called off the main thread.
 */
class TwinStore(
    context: Context,
    private val file: File,
    private val cipher: TwinCipher,
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Long = MAX_BYTES
) {
    private val appContext = context.applicationContext
    private var helper: Helper? = null

    @Synchronized
    fun add(text: String, packageName: String?, atMillis: Long): Long {
        val payload = JSONObject().put("v", 1).put("t", text).apply {
            if (packageName != null) put("p", packageName)
        }.toString().toByteArray(Charsets.UTF_8)
        val sealed = cipher.seal(payload, aad(atMillis))
        val db = db()
        val id = db.insertOrThrow(TABLE, null, ContentValues().apply {
            put(COL_AT, atMillis)
            put(COL_IV, sealed.iv)
            put(COL_BODY, sealed.ciphertext)
        })
        prune(db)
        return id
    }

    @Synchronized
    fun count(): Int = if (!file.exists()) 0 else
        db().rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { it.moveToFirst(); it.getInt(0) }

    @Synchronized
    fun storedBytes(): Long = if (!file.exists()) 0 else
        db().rawQuery("SELECT COALESCE(SUM(LENGTH($COL_BODY)), 0) FROM $TABLE", null)
            .use { it.moveToFirst(); it.getLong(0) }

    /** Newest first. Rows that no longer open (key destroyed or tampered with) are skipped. */
    @Synchronized
    fun recent(limit: Int, offset: Int = 0): List<TwinEntry> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<TwinEntry>()
        db().rawQuery(
            "SELECT $COL_ID, $COL_AT, $COL_IV, $COL_BODY FROM $TABLE ORDER BY $COL_AT DESC, $COL_ID DESC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString())
        ).use { c ->
            while (c.moveToNext()) decode(c.getLong(0), c.getLong(1), c.getBlob(2), c.getBlob(3))?.let(out::add)
        }
        return out
    }

    /** Calls [block] with every readable entry, oldest first, a page at a time. */
    fun forEach(pageSize: Int = 500, block: (TwinEntry) -> Unit) {
        var lastId = 0L
        while (true) {
            val (rows, maxId) = synchronized(this) {
                if (!file.exists()) return
                val rows = ArrayList<TwinEntry>()
                var maxId = lastId
                db().rawQuery(
                    "SELECT $COL_ID, $COL_AT, $COL_IV, $COL_BODY FROM $TABLE WHERE $COL_ID > ? ORDER BY $COL_ID LIMIT ?",
                    arrayOf(lastId.toString(), pageSize.toString())
                ).use { c ->
                    while (c.moveToNext()) {
                        maxId = c.getLong(0)
                        decode(maxId, c.getLong(1), c.getBlob(2), c.getBlob(3))?.let(rows::add)
                    }
                }
                rows to maxId
            }
            if (maxId == lastId) return
            lastId = maxId
            rows.forEach(block)
        }
    }

    @Synchronized
    fun delete(id: Long): Boolean = file.exists() && db().delete(TABLE, "$COL_ID = ?", arrayOf(id.toString())) > 0

    /** Deletes every row, the database files and the encryption key. */
    @Synchronized
    fun deleteAll() {
        helper?.close()
        helper = null
        SQLiteDatabase.deleteDatabase(file)
        file.parentFile?.listFiles { f -> f.name.startsWith(file.name) }?.forEach { it.delete() }
        cipher.destroyKey()
    }

    @Synchronized
    fun close() {
        helper?.close()
        helper = null
    }

    private fun decode(id: Long, at: Long, iv: ByteArray, body: ByteArray): TwinEntry? = runCatching {
        val json = JSONObject(String(cipher.open(TwinCipher.Sealed(iv, body), aad(at)), Charsets.UTF_8))
        TwinEntry(id, at, json.getString("t"), json.optString("p").takeIf { it.isNotEmpty() })
    }.getOrNull()

    private fun prune(db: SQLiteDatabase) {
        val excess = rowCount(db) - maxEntries
        if (excess > 0) {
            db.execSQL(
                "DELETE FROM $TABLE WHERE $COL_ID IN (SELECT $COL_ID FROM $TABLE ORDER BY $COL_AT, $COL_ID LIMIT $excess)"
            )
        }
        var bytes = db.rawQuery("SELECT COALESCE(SUM(LENGTH($COL_BODY)), 0) FROM $TABLE", null)
            .use { it.moveToFirst(); it.getLong(0) }
        while (bytes > maxBytes) {
            val oldest = db.rawQuery(
                "SELECT $COL_ID, LENGTH($COL_BODY) FROM $TABLE ORDER BY $COL_AT, $COL_ID LIMIT 1", null
            ).use { if (it.moveToFirst()) it.getLong(0) to it.getLong(1) else null } ?: break
            db.delete(TABLE, "$COL_ID = ?", arrayOf(oldest.first.toString()))
            bytes -= oldest.second
        }
    }

    private fun rowCount(db: SQLiteDatabase): Int =
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { it.moveToFirst(); it.getInt(0) }

    private fun db(): SQLiteDatabase {
        val h = helper ?: Helper(appContext, file).also { helper = it }
        return h.writableDatabase
    }

    private class Helper(context: Context, file: File) :
        SQLiteOpenHelper(context, file.also { it.parentFile?.mkdirs() }.absolutePath, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            // A PRAGMA that returns a row must go through rawQuery on Android.
            db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE ($COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_AT INTEGER NOT NULL, $COL_IV BLOB NOT NULL, $COL_BODY BLOB NOT NULL)"
            )
            db.execSQL("CREATE INDEX idx_${TABLE}_at ON $TABLE ($COL_AT)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        const val MAX_ENTRIES = 100_000
        const val MAX_BYTES = 64L * 1024 * 1024
        private const val TABLE = "twin_entries"
        private const val COL_ID = "id"
        private const val COL_AT = "created_at"
        private const val COL_IV = "iv"
        private const val COL_BODY = "body"

        private fun aad(atMillis: Long) = "twin-v1:$atMillis".toByteArray(Charsets.UTF_8)

        /** The production store, in the never-backed-up directory. */
        fun storeFile(context: Context) = File(context.noBackupFilesDir, "twin/twin_store.db")
    }
}
