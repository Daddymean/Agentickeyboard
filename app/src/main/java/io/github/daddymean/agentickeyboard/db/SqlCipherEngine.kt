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
 * through [SqlCipherOpenHelperFactory], not the library factory that passes a null handler. In 4.20.0 the default handler also keeps a codec file, but the explicit handler is
 * the guarantee this code relies on.
 */
class SqlCipherEngine : KeyboardDbEngine {

    override fun load() {
        System.loadLibrary("sqlcipher")
    }

    override fun exportEncrypted(plain: File, dest: File, sqlKey: ByteArray): DbSnapshot {
        require(!dest.exists()) { "copy target exists" }
        val db = SQLiteDatabase.openDatabase(plain.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, KeepFile(), null)
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                check(!c.moveToFirst() || c.getInt(0) == 0) { "checkpoint busy" }
            }
            val source = snapshot(db)
            // Bind the path; put the raw-key passphrase in the SQL. Binding the x'…' form as a
            // BLOB makes SQLCipher treat it as a passphrase, not a raw key, and the export fails.
            val passphrase = String(sqlKey, Charsets.US_ASCII)
            require(passphrase.matches(Regex("x'[0-9a-f]{64}'"))) { "bad raw key format" }
            dest.parentFile?.mkdirs()
            // ATTACH of a missing file returned ENOENT on the device (SQLCipher didn't create it).
            // Open-or-create the encrypted target first, then export into it.
            check(!dest.exists()) { "copy target exists" }
            SQLiteDatabase.openOrCreateDatabase(dest, sqlKey, null, KeepFile(), null).close()
            val path = dest.canonicalPath.replace("'", "''")
            db.execSQL("ATTACH DATABASE '" + path + "' AS $ALIAS KEY \"" + passphrase + "\"")
            try {
                db.rawQuery("SELECT sqlcipher_export('$ALIAS')", null).use { it.moveToFirst() }
                db.execSQL("PRAGMA $ALIAS.user_version = ${source.userVersion}")
            } finally {
                db.execSQL("DETACH DATABASE $ALIAS")
            }
            return source
        } finally {
            db.close()
        }
    }

    override fun verify(file: File, sqlKey: ByteArray): DbSnapshot {
        val db = SQLiteDatabase.openDatabase(file.path, sqlKey, null, SQLiteDatabase.OPEN_READONLY, KeepFile(), null)
        try {
            // cipher_integrity_check returns no rows when every page's HMAC checks out.
            db.rawQuery("PRAGMA cipher_integrity_check", null).use { check(it.count == 0) { "cipher integrity" } }
            db.rawQuery("PRAGMA integrity_check", null).use { c ->
                check(c.moveToFirst() && c.getString(0) == "ok" && c.count == 1) { "integrity" }
            }
            return snapshot(db)
        } finally {
            db.close()
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
        classifyOpenFailure(t)
    }

    override fun factory(sqlKey: ByteArray, onCorruption: (File) -> Unit): SupportSQLiteOpenHelper.Factory =
        SqlCipherOpenHelperFactory(sqlKey, onCorruption)

    internal fun snapshot(db: SQLiteDatabase): DbSnapshot = snapshotOf(db) { sql, args ->
        db.rawQuery(sql, args)
    }

    private companion object {
        const val ALIAS = "kbcrypt"
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

/** True for SQLITE_NOTADB / corruption only, not for transient I/O or busy. */
fun classifyOpenFailure(t: Throwable): OpenProbe {
    var e: Throwable? = t
    while (e != null) {
        if (e is android.database.sqlite.SQLiteDatabaseCorruptException) return OpenProbe.Unopenable
        val msg = e.message.orEmpty().lowercase()
        if (msg.contains("file is not a database") || msg.contains("not a database")) return OpenProbe.Unopenable
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
 * Room's open-helper factory for the encrypted keyboard database. Unlike the library's default Room factory, this passes [KeepFile] into `SQLiteOpenHelper` so a damaged page
 * or a wrong key never deletes the file. [onCorruption] may move it aside after the exception
 * has been reported.
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

/** Error handler that never deletes the database file. */
class KeepFile(private val onCorrupt: (String) -> Unit = {}) : DatabaseErrorHandler {
    override fun onCorruption(dbObj: SQLiteDatabase?, exception: SQLiteException?) {
        val path = dbObj?.path ?: return
        if (path.isEmpty() || path == ":memory:") return
        onCorrupt(path)
    }
}
