package io.github.daddymean.agentickeyboard.db

import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.io.File

/**
 * [KeyboardDbEngine] on SQLCipher for Android 4 (BSD-3-Clause, Zetetic LLC; see
 * `third_party/sqlcipher/NOTICE`). Default SQLCipher 4 settings: AES-256-CBC pages with an
 * HMAC-SHA512 per page; the key is raw (`x'…'`), so there is no PBKDF2 on open.
 *
 * Every connection opened here uses [KeepFile] as its error handler, so a wrong key or a damaged
 * file is reported as a failure and never "repaired" by deleting the database. Room traffic goes
 * through [SqlCipherOpenHelperFactory], not the library factory that passes a null handler. In
 * 4.20.0 the default handler also keeps a codec file, but the explicit handler is the guarantee
 * this code relies on. [KeepFile] only flags corruption; the file is moved aside at the next
 * [KeyboardDbMigrator.prepare], before any connection opens.
 *
 * The raw key is only ever passed as a `ByteArray` to SQLCipher's open APIs. It is never built
 * into SQL text, and any failure is rethrown with the key stripped from the message chain.
 */
class SqlCipherEngine : KeyboardDbEngine {

    override fun load() {
        System.loadLibrary("sqlcipher")
    }

    override fun exportEncrypted(plain: File, dest: File, sqlKey: ByteArray): DbSnapshot {
        require(!dest.exists()) { "copy target exists" }
        require(isRawKey(sqlKey)) { "bad raw key format" }
        val source = SQLiteDatabase.openDatabase(plain.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, KeepFile(), null).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                check(!c.moveToFirst() || c.getInt(0) == 0) { "checkpoint busy" }
            }
            snapshot(db)
        }
        dest.parentFile?.mkdirs()
        check(!dest.exists()) { "copy target exists" }
        // Open the encrypted target with the byte-array key API, then ATTACH the plaintext
        // (no key) and export into it. The raw key never appears in SQL text.
        try {
            val destDb = SQLiteDatabase.openOrCreateDatabase(dest, sqlKey, null, KeepFile(), null)
            try {
                destDb.execSQL("ATTACH DATABASE ? AS $ALIAS KEY ''", arrayOf(plain.canonicalPath))
                try {
                    destDb.rawQuery("SELECT sqlcipher_export('main', '$ALIAS')", null).use { it.moveToFirst() }
                    destDb.execSQL("PRAGMA user_version = ${source.userVersion}")
                } finally {
                    destDb.execSQL("DETACH DATABASE $ALIAS")
                }
            } finally {
                destDb.close()
            }
        } catch (t: Throwable) {
            throw withoutKey(t, sqlKey)
        }
        return source
    }

    override fun verify(file: File, sqlKey: ByteArray): DbSnapshot {
        val db = SQLiteDatabase.openDatabase(file.path, sqlKey, null, SQLiteDatabase.OPEN_READONLY, KeepFile(), null)
        try {
            // Full checks are for migration and the occasional background pass, not every IME open.
            db.rawQuery("PRAGMA cipher_integrity_check", null).use { check(it.count == 0) { "cipher integrity" } }
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                check(c.moveToFirst() && c.getString(0) == "ok" && c.count == 1) { "integrity" }
            }
            return snapshot(db)
        } catch (t: Throwable) {
            throw withoutKey(t, sqlKey)
        } finally {
            db.close()
        }
    }

    override fun integrity(file: File, sqlKey: ByteArray, quick: Boolean): IntegrityProbe {
        return try {
            val db = SQLiteDatabase.openDatabase(file.path, sqlKey, null, SQLiteDatabase.OPEN_READONLY, KeepFile(), null)
            try {
                if (quick) {
                    // quick_check is cheap enough for every open; a non-ok row is real corruption.
                    db.rawQuery("PRAGMA quick_check", null).use { c ->
                        if (c.count == 1 && c.moveToFirst() && c.getString(0) == "ok") IntegrityProbe.Ok
                        else IntegrityProbe.Corrupt
                    }
                } else {
                    db.rawQuery("PRAGMA cipher_integrity_check", null).use {
                        if (it.count != 0) return IntegrityProbe.Corrupt
                    }
                    db.rawQuery("PRAGMA integrity_check", null).use { c ->
                        if (c.moveToFirst() && c.getString(0) == "ok" && c.count == 1) IntegrityProbe.Ok
                        else IntegrityProbe.Corrupt
                    }
                }
            } finally {
                db.close()
            }
        } catch (t: Throwable) {
            // Only a real corruption exception or a non-ok pragma result is Corrupt.
            // Disk I/O, busy, and OOM stay Transient so one blip cannot set the database aside.
            if (classifyOpenFailure(t) == OpenProbe.Unopenable) IntegrityProbe.Corrupt else IntegrityProbe.Transient
        }
    }

    override fun snapshotPlaintext(file: File): DbSnapshot {
        val db = SQLiteDatabase.openDatabase(file.path, ByteArray(0), null, SQLiteDatabase.OPEN_READONLY, KeepFile(), null)
        try {
            return snapshot(db)
        } finally {
            db.close()
        }
    }

    override fun canOpen(file: File, sqlKey: ByteArray): OpenProbe = try {
        // Read-only, so the probe never changes the journal mode or anything else in the file.
        val db = SQLiteDatabase.openDatabase(file.path, sqlKey, null, SQLiteDatabase.OPEN_READONLY, KeepFile(), null)
        try {
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        } finally {
            db.close()
        }
        OpenProbe.Ok
    } catch (t: Throwable) {
        // Classify the original exception first: withoutKey must not change the result.
        classifyOpenFailure(t)
    }

    override fun factory(sqlKey: ByteArray, onCorruption: (File) -> Unit): SupportSQLiteOpenHelper.Factory =
        SqlCipherOpenHelperFactory(sqlKey, onCorruption)

    internal fun snapshot(db: SQLiteDatabase): DbSnapshot = snapshotOf(db) { sql, args ->
        db.rawQuery(sql, args)
    }

    private companion object {
        const val ALIAS = "kbplain"
    }
}

/** What a probe open of an encrypted file found. */
sealed interface OpenProbe {
    data object Ok : OpenProbe
    /** Wrong key, not a database, or a damaged page: the file will not open with this key. */
    data object Unopenable : OpenProbe
    /** I/O, busy, OOM, or any other failure: leave the file alone and retry later. */
    data object Transient : OpenProbe
}

/** Result of [KeyboardDbEngine.integrity]. Not the same as a thrown exception. */
sealed interface IntegrityProbe {
    data object Ok : IntegrityProbe
    /** The check returned a non-ok result, or SQLite reported a corrupt / malformed image. */
    data object Corrupt : IntegrityProbe
    /** Disk I/O, busy, OOM, or any other failure: do not flag or move the file. */
    data object Transient : IntegrityProbe
}

/** True for SQLITE_NOTADB / corruption only, not for transient I/O or busy. */
fun classifyOpenFailure(t: Throwable): OpenProbe {
    var e: Throwable? = t
    while (e != null) {
        if (e is android.database.sqlite.SQLiteDatabaseCorruptException) return OpenProbe.Unopenable
        val msg = e.message.orEmpty().lowercase()
        if (msg.contains("file is not a database") || msg.contains("not a database") ||
            msg.contains("database disk image is malformed") || msg.contains("disk image is malformed")) {
            return OpenProbe.Unopenable
        }
        e = e.cause
    }
    return OpenProbe.Transient
}

/**
 * Reads user_version, the row count of every table (including `sqlite_sequence` when present)
 * and the number of `sqlite_master` entries, so a copy can be checked for a missing index as
 * well as a missing row.
 */
internal fun snapshotOf(db: Any, query: (String, Array<String>?) -> android.database.Cursor): DbSnapshot {
    val version = query("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
    val schemaEntries = query("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst(); it.getInt(0) }
    val tables = query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND (name NOT LIKE 'sqlite_%' OR name = 'sqlite_sequence') ORDER BY name",
        null
    ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    val counts = tables.associateWith { t ->
        query("SELECT count(*) FROM \"${t.replace("\"", "\"\"")}\"", null).use { it.moveToFirst(); it.getLong(0) }
    }
    return DbSnapshot(version, counts, schemaEntries)
}

/**
 * Room's open-helper factory for the encrypted keyboard database. Unlike the library's default
 * Room factory, this passes [KeepFile] into `SQLiteOpenHelper` so a damaged page or a wrong key
 * never deletes the file. [onCorruption] only flags the file; it must not rename or delete it
 * while connections may still be open.
 */
class SqlCipherOpenHelperFactory(
    private val sqlKey: ByteArray,
    private val onCorruption: (File) -> Unit = {},
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val cb = configuration.callback
        return object : SQLiteOpenHelper(
            configuration.context,
            configuration.name,
            sqlKey,
            null,
            cb.version,
            0,
            KeepFile { path -> onCorruption(File(path)) },
            null,
            false,
        ) {
            override fun onConfigure(db: SQLiteDatabase) = cb.onConfigure(db)
            override fun onCreate(db: SQLiteDatabase) = cb.onCreate(db)
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = cb.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = cb.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SQLiteDatabase) = cb.onOpen(db)
        }
    }
}

/**
 * Error handler that never deletes or moves the database file. A reported corruption is passed
 * to [onCorrupt] so a persistent marker can be written; the move-aside happens at the next
 * launch, before any connection opens.
 */
class KeepFile(private val onCorrupt: (String) -> Unit = {}) : DatabaseErrorHandler {
    override fun onCorruption(dbObj: SQLiteDatabase?, exception: SQLiteException?) {
        val path = dbObj?.path ?: return
        if (path.isEmpty() || path == ":memory:") return
        onCorrupt(path)
    }
}

/** True when [sqlKey] is the raw-key form `x'<64 hex digits>'` as US-ASCII bytes. */
internal fun isRawKey(sqlKey: ByteArray): Boolean {
    if (sqlKey.size != 67) return false
    if (sqlKey[0] != 'x'.code.toByte() || sqlKey[1] != '\''.code.toByte() || sqlKey[66] != '\''.code.toByte()) return false
    return (2 until 66).all { i ->
        val c = sqlKey[i].toInt().toChar()
        c in '0'..'9' || c in 'a'..'f'
    }
}

/**
 * Returns a new exception chain whose messages do not contain the raw-key bytes. The original
 * class is kept so callers can still classify corruption. The original throwable is not used
 * as a cause, so a later log of this result cannot leak the key.
 */
internal fun withoutKey(t: Throwable, sqlKey: ByteArray): Throwable {
    val needles = buildList {
        val raw = String(sqlKey, Charsets.US_ASCII)
        if (raw.isNotEmpty()) add(raw)
        if (isRawKey(sqlKey)) add(sqlKey.decodeToString(2, 66))
    }.filter { it.length >= 8 }.distinct()
    if (needles.isEmpty()) return t
    fun clean(s: String?): String? {
        if (s == null) return null
        var out: String = s
        for (n in needles) out = out.replace(n, "<redacted>")
        return out
    }
    fun sameClass(e: Throwable, msg: String): Throwable {
        val cls = e.javaClass
        return runCatching { cls.getConstructor(String::class.java).newInstance(msg) as Throwable }
            .recoverCatching { cls.getDeclaredConstructor(String::class.java).apply { isAccessible = true }.newInstance(msg) as Throwable }
            .getOrElse { Exception(msg) }
    }
    fun copyAll(e: Throwable): Throwable {
        val out = sameClass(e, clean(e.message) ?: "error")
        out.stackTrace = e.stackTrace
        e.cause?.let { runCatching { out.initCause(copyAll(it)) } }
        return out
    }
    return copyAll(t)
}
