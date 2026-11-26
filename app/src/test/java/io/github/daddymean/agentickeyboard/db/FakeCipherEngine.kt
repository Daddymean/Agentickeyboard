package io.github.daddymean.agentickeyboard.db

import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.daddymean.agentickeyboard.twin.TwinCipher
import java.io.File
import java.io.RandomAccessFile

/**
 * SQLCipher's native library can't load under Robolectric, so the migration state machine is
 * tested with this stand-in. A file counts as "encrypted" when its SQLite header carries
 * [APP_ID] (offset 68), and the key it was "encrypted" with is kept in a [KEY_TABLE] row, so a
 * wrong key fails to open just like SQLCipher. Everything else (files, renames, WAL, Room) is real.
 */
class FakeCipherEngine : KeyboardDbEngine {
    var loadFails = false
    var exportFails = false
    var corruptCopy = false
    var exports = 0
    var forcedProbe: OpenProbe? = null
    /** One-shot error consumed by [integrity]; classified, never thrown to the caller. */
    var integrityError: Throwable? = null
    /** When set, [integrity] returns this and ignores the file. */
    var integrityForced: IntegrityProbe? = null

    override fun load() {
        if (loadFails) throw UnsatisfiedLinkError("no sqlcipher here")
    }

    override fun exportEncrypted(plain: File, dest: File, sqlKey: ByteArray): DbSnapshot {
        require(!dest.exists())
        exports++
        if (exportFails) {
            dest.writeBytes(ByteArray(4096) { 7 }) // a partial copy left behind
            error("disk full")
        }
        val src = SQLiteDatabase.openDatabase(plain.path, null, SQLiteDatabase.OPEN_READWRITE)
        val snapshot = try {
            src.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { check(!it.moveToFirst() || it.getInt(0) == 0) }
            snapshot(src)
        } finally {
            src.close()
        }
        plain.copyTo(dest)
        val copy = SQLiteDatabase.openDatabase(dest.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            stamp(copy, sqlKey)
            if (corruptCopy) copy.execSQL("DELETE FROM user_vocabulary WHERE rowid IN (SELECT rowid FROM user_vocabulary LIMIT 1)")
        } finally {
            copy.close()
        }
        return snapshot
    }

    override fun verify(file: File, sqlKey: ByteArray): DbSnapshot {
        check(canOpen(file, sqlKey) == OpenProbe.Ok)
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
            return snapshot(db)
        } finally {
            db.close()
        }
    }

    override fun integrity(file: File, sqlKey: ByteArray, quick: Boolean): IntegrityProbe {
        integrityError?.let { err ->
            integrityError = null
            return if (classifyOpenFailure(err) == OpenProbe.Unopenable) IntegrityProbe.Corrupt else IntegrityProbe.Transient
        }
        integrityForced?.let { return it }
        if (canOpen(file, sqlKey) != OpenProbe.Ok) {
            return if (canOpen(file, sqlKey) == OpenProbe.Unopenable) IntegrityProbe.Corrupt else IntegrityProbe.Transient
        }
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val pragma = if (quick) "PRAGMA quick_check" else "PRAGMA integrity_check"
            return db.rawQuery(pragma, null).use { c ->
                if (c.moveToFirst() && c.getString(0) == "ok") IntegrityProbe.Ok else IntegrityProbe.Corrupt
            }
        } catch (t: Throwable) {
            return if (classifyOpenFailure(t) == OpenProbe.Unopenable) IntegrityProbe.Corrupt else IntegrityProbe.Transient
        } finally {
            db.close()
        }
    }

    override fun snapshotPlaintext(file: File): DbSnapshot {
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try { return snapshot(db) } finally { db.close() }
    }

    override fun canOpen(file: File, sqlKey: ByteArray): OpenProbe {
        forcedProbe?.let { return it }
        val ok = runCatching {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                db.rawQuery("SELECT k FROM $KEY_TABLE", null).use { it.moveToFirst() && it.getBlob(0).contentEquals(sqlKey) }
            } finally {
                db.close()
            }
        }.getOrDefault(false)
        return if (ok) OpenProbe.Ok else OpenProbe.Unopenable
    }

    override fun isEncrypted(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { f -> f.seek(68); f.readInt() == APP_ID }
    }.getOrDefault(false)

    /** Room gets the framework helper; a database it creates is stamped "encrypted" with [sqlKey]. */
    override fun factory(sqlKey: ByteArray, onCorruption: (File) -> Unit): SupportSQLiteOpenHelper.Factory = SupportSQLiteOpenHelper.Factory { config ->
        val cb = config.callback
        val stamping = object : SupportSQLiteOpenHelper.Callback(cb.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = cb.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA application_id = $APP_ID")
                db.execSQL("CREATE TABLE IF NOT EXISTS $KEY_TABLE (k BLOB)")
                db.execSQL("INSERT INTO $KEY_TABLE VALUES (?)", arrayOf<Any>(sqlKey))
                cb.onCreate(db)
            }
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = cb.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = cb.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = cb.onOpen(db)
        }
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(stamping).build()
        )
    }

    private fun stamp(db: SQLiteDatabase, sqlKey: ByteArray) {
        db.execSQL("PRAGMA application_id = $APP_ID")
        db.execSQL("CREATE TABLE IF NOT EXISTS $KEY_TABLE (k BLOB)")
        db.execSQL("DELETE FROM $KEY_TABLE")
        db.execSQL("INSERT INTO $KEY_TABLE VALUES (?)", arrayOf<Any>(sqlKey))
    }

    private fun snapshot(db: SQLiteDatabase): DbSnapshot {
        val version = db.rawQuery("PRAGMA user_version", null).use { it.moveToFirst(); it.getInt(0) }
        val schemaEntries = db.rawQuery(
            "SELECT count(*) FROM sqlite_master WHERE name != '$KEY_TABLE'", null
        ).use { it.moveToFirst(); it.getInt(0) }
        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND (name NOT LIKE 'sqlite_%' OR name = 'sqlite_sequence') AND name != '$KEY_TABLE' ORDER BY name",
            null
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        return DbSnapshot(version, tables.associateWith { t -> db.rawQuery("SELECT count(*) FROM `$t`", null).use { it.moveToFirst(); it.getLong(0) } }, schemaEntries)
    }

    companion object {
        const val APP_ID = 0x4B42454E // "KBEN"
        const val KEY_TABLE = "fake_cipher_key"
    }
}

/** In-memory stand-in for the Keystore wrapping key. */
class FakeKeyWrapper : DbKeyWrapper {
    var present = true
    var failUnwraps = 0
    var unwraps = 0
    var throwInvalidKey = false
    var throwPermanentlyInvalidated = false

    override fun wrap(raw: ByteArray): TwinCipher.Sealed {
        present = true
        return TwinCipher.Sealed(byteArrayOf(1, 2, 3), raw.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
    }

    override fun unwrap(sealed: TwinCipher.Sealed): ByteArray {
        unwraps++
        check(present) { "key is gone" }
        if (throwPermanentlyInvalidated) throw android.security.keystore.KeyPermanentlyInvalidatedException("invalidated")
        if (throwInvalidKey) throw java.security.InvalidKeyException("Keystore operation failed")
        if (failUnwraps > 0) { failUnwraps--; error("keystore busy") }
        return sealed.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
    }

    override fun keyExists() = present
}
