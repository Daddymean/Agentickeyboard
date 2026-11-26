package io.github.daddymean.agentickeyboard.db

import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * [KeyboardDbEngine] on SQLCipher for Android 4 (BSD-3-Clause, Zetetic LLC; see
 * `third_party/sqlcipher/NOTICE`). Default SQLCipher 4 settings: AES-256-CBC pages with an
 * HMAC-SHA512 per page; the key is raw (`x'…'`), so there is no PBKDF2 on open.
 *
 * Every connection opened here uses [KeepFile] as its error handler, so a wrong key or a damaged
 * file is reported as a failure and never "repaired" by deleting the database.
 */
class SqlCipherEngine : KeyboardDbEngine {

    override fun load() {
        System.loadLibrary("sqlcipher")
    }

    override fun exportEncrypted(plain: File, dest: File, sqlKey: ByteArray): DbSnapshot {
        require(!dest.exists()) { "copy target exists" }
        val db = SQLiteDatabase.openDatabase(plain.path, ByteArray(0), null, SQLiteDatabase.OPEN_READWRITE, KeepFile, null)
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c ->
                check(!c.moveToFirst() || c.getInt(0) == 0) { "checkpoint busy" }
            }
            val source = snapshot(db)
            db.execSQL("ATTACH DATABASE ? AS $ALIAS KEY ?", arrayOf<Any>(dest.path, sqlKey))
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
        val db = SQLiteDatabase.openDatabase(file.path, sqlKey, null, SQLiteDatabase.OPEN_READONLY, KeepFile, null)
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

    override fun canOpen(file: File, sqlKey: ByteArray): Boolean = runCatching {
        // Read-only, so the probe never changes the journal mode or anything else in the file.
        val db = SQLiteDatabase.openDatabase(file.path, sqlKey, null, SQLiteDatabase.OPEN_READONLY, KeepFile, null)
        try {
            db.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        } finally {
            db.close()
        }
    }.isSuccess

    override fun factory(sqlKey: ByteArray): SupportSQLiteOpenHelper.Factory = SupportOpenHelperFactory(sqlKey)

    private fun snapshot(db: SQLiteDatabase): DbSnapshot {
        val version = db.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null)
            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        val counts = tables.associateWith { t ->
            db.rawQuery("SELECT count(*) FROM \"${t.replace("\"", "\"\"")}\"", null).use { it.moveToFirst(); it.getLong(0) }
        }
        return DbSnapshot(version, counts)
    }

    private object KeepFile : DatabaseErrorHandler {
        override fun onCorruption(dbObj: SQLiteDatabase?, exception: android.database.sqlite.SQLiteException?) = Unit
    }

    private companion object {
        const val ALIAS = "kbcrypt"
    }
}
