package io.github.daddymean.agentickeyboard.twin.journal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.daddymean.agentickeyboard.twin.TwinCipher
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** One decrypted journal record. [body] holds the kind-specific fields. */
data class JournalRecord(val id: Long, val atMillis: Long, val kind: String, val body: JSONObject)

/**
 * KEYBOARD-025: the journal's encrypted store, next to the twin store in
 * `noBackupFilesDir/twin/`.
 *
 * - Rows are `(id, created_at, iv, body)` like [io.github.daddymean.agentickeyboard.twin.TwinStore]:
 *   `body` is AES-256-GCM ciphertext (same [TwinCipher] and Keystore key) of a JSON
 *   object holding the kind and every field: answers, transcripts, child names,
 *   instrument responses, settings, skips. Only the timestamp is clear text, and it is
 *   bound into the tag.
 * - Recordings are FLAC sealed chunk by chunk ([SealedAudioWriter]) in `twin/audio/`.
 * - Nothing here is ever deleted automatically. [delete] removes a record and its
 *   recordings; [deleteAll] removes the database and every recording. The key itself is
 *   destroyed by the twin store's delete-all, which runs right after (see
 *   [io.github.daddymean.agentickeyboard.twin.TwinVault]).
 * Every method does disk or Keystore work, so call it off the main thread.
 */
class JournalStore(
    context: Context,
    private val dir: File,
    private val cipher: TwinCipher
) {
    private val appContext = context.applicationContext
    private val file = File(dir, DB_NAME)
    private val audioDir = File(dir, AUDIO_DIR)
    private var helper: Helper? = null

    @Synchronized
    fun add(kind: String, body: JSONObject, atMillis: Long): Long {
        val payload = JSONObject(body.toString()).put("kind", kind).put("v", 1).toString().toByteArray(Charsets.UTF_8)
        val sealed = cipher.seal(payload, aad(atMillis))
        return db().insertOrThrow(TABLE, null, ContentValues().apply {
            put(COL_AT, atMillis)
            put(COL_IV, sealed.iv)
            put(COL_BODY, sealed.ciphertext)
        })
    }

    /** Every readable record, oldest first. */
    @Synchronized
    fun all(): List<JournalRecord> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<JournalRecord>()
        db().rawQuery("SELECT $COL_ID, $COL_AT, $COL_IV, $COL_BODY FROM $TABLE ORDER BY $COL_AT, $COL_ID", null).use { c ->
            while (c.moveToNext()) decode(c.getLong(0), c.getLong(1), c.getBlob(2), c.getBlob(3))?.let(out::add)
        }
        return out
    }

    @Synchronized
    fun count(): Int = if (!file.exists()) 0 else
        db().rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { it.moveToFirst(); it.getInt(0) }

    /** Deletes one record and any recordings it references. */
    @Synchronized
    fun delete(id: Long): Boolean {
        if (!file.exists()) return false
        val record = db().rawQuery("SELECT $COL_ID, $COL_AT, $COL_IV, $COL_BODY FROM $TABLE WHERE $COL_ID = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) decode(c.getLong(0), c.getLong(1), c.getBlob(2), c.getBlob(3)) else null }
        record?.let { audioIds(it.body).forEach(::deleteAudio) }
        return db().delete(TABLE, "$COL_ID = ?", arrayOf(id.toString())) > 0
    }

    /** A new recording: returns its id and an encrypting stream to write FLAC into. */
    fun newRecording(): Pair<String, SealedAudioWriter> {
        audioDir.mkdirs()
        val id = UUID.randomUUID().toString()
        return id to SealedAudioWriter(FileOutputStream(audioFile(id)), cipher, id)
    }

    /** The decrypted FLAC bytes of a recording. */
    fun readRecording(fileId: String): ByteArray =
        audioFile(fileId).inputStream().buffered().use { SealedAudioReader.readAll(it, cipher, fileId) }

    fun deleteAudio(fileId: String) {
        audioFile(fileId).delete()
    }

    /**
     * Recordings no saved record points to: takes left behind when an answer screen was
     * closed without Save or Discard. They are never deleted automatically; the answers
     * screen lists them so they can be played and deleted.
     */
    @Synchronized
    fun unsavedRecordings(records: List<JournalRecord> = all()): List<String> {
        val referenced = records.flatMap { audioIds(it.body) }.toSet()
        return (audioDir.listFiles()?.toList() ?: emptyList())
            .filter { it.name.endsWith(AUDIO_SUFFIX) }
            .sortedBy { it.lastModified() }
            .map { it.name.removeSuffix(AUDIO_SUFFIX) }
            .filter { it !in referenced && it.matches(ID_PATTERN) }
    }

    /** Bytes on disk: the database files plus every recording. */
    fun fileBytes(): Long =
        (dir.listFiles { f -> f.name.startsWith(DB_NAME) }?.sumOf { it.length() } ?: 0L) +
            (audioDir.listFiles()?.sumOf { it.length() } ?: 0L)

    /** Deletes the database files and every recording. The key is destroyed by the caller. */
    @Synchronized
    fun deleteAll() {
        helper?.close()
        helper = null
        SQLiteDatabase.deleteDatabase(file)
        dir.listFiles { f -> f.name.startsWith(DB_NAME) }?.forEach { it.delete() }
        audioDir.deleteRecursively()
    }

    @Synchronized
    fun close() {
        helper?.close()
        helper = null
    }

    private fun audioFile(fileId: String): File {
        require(fileId.matches(ID_PATTERN)) { "bad recording id" }
        return File(audioDir, "$fileId$AUDIO_SUFFIX")
    }

    private fun decode(id: Long, at: Long, iv: ByteArray, body: ByteArray): JournalRecord? = runCatching {
        val json = JSONObject(String(cipher.open(TwinCipher.Sealed(iv, body), aad(at)), Charsets.UTF_8))
        JournalRecord(id, at, json.getString("kind"), json)
    }.getOrNull()

    private fun db(): SQLiteDatabase {
        val h = helper ?: Helper(appContext, file).also { helper = it }
        return h.writableDatabase
    }

    private class Helper(context: Context, file: File) :
        SQLiteOpenHelper(context, file.also { it.parentFile?.mkdirs() }.absolutePath, null, 1) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.rawQuery("PRAGMA secure_delete = ON", null).use { it.moveToFirst() }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE ($COL_ID INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "$COL_AT INTEGER NOT NULL, $COL_IV BLOB NOT NULL, $COL_BODY BLOB NOT NULL)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    companion object {
        const val DB_NAME = "journal.db"
        const val AUDIO_DIR = "audio"
        const val AUDIO_SUFFIX = ".flac.sealed"
        private val ID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private const val TABLE = "journal_records"
        private const val COL_ID = "id"
        private const val COL_AT = "created_at"
        private const val COL_IV = "iv"
        private const val COL_BODY = "body"

        private fun aad(atMillis: Long) = "journal-v1:$atMillis".toByteArray(Charsets.UTF_8)

        /** Recording ids referenced by a record (`audio` array of `{file, ms}`). */
        fun audioIds(body: JSONObject): List<String> {
            val a = body.optJSONArray("audio") ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("file")?.takeIf(String::isNotEmpty) }
        }

        /** The production directory, never backed up. */
        fun dir(context: Context) = File(context.noBackupFilesDir, "twin")
    }
}
