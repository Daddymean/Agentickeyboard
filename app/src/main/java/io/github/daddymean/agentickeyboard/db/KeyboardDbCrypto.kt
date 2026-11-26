package io.github.daddymean.agentickeyboard.db

import android.content.Context
import android.os.SystemClock
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.io.RandomAccessFile

/** A copy of a database's shape: user_version and the row count of every table. */
data class DbSnapshot(val userVersion: Int, val counts: Map<String, Long>)

/**
 * The SQLCipher calls the migration needs. [SqlCipherEngine] in the app; tests use a stand-in,
 * because SQLCipher's native library only exists for Android ABIs and can't load under Robolectric.
 */
interface KeyboardDbEngine {
    /** Throws when the engine can't run here (for example the native library is missing). */
    fun load()

    /**
     * Checkpoints and empties [plain]'s WAL, then writes an encrypted copy to [dest] (which must
     * not exist). Returns the source snapshot taken in the same session.
     */
    fun exportEncrypted(plain: File, dest: File, sqlKey: ByteArray): DbSnapshot

    /** Opens encrypted [file] with [sqlKey], runs the integrity checks and returns its snapshot. Throws on any failure. */
    fun verify(file: File, sqlKey: ByteArray): DbSnapshot

    /** Cheap check that [sqlKey] opens [file]. Must never delete or change the file, even when it fails. */
    fun canOpen(file: File, sqlKey: ByteArray): Boolean

    fun factory(sqlKey: ByteArray): SupportSQLiteOpenHelper.Factory

    /** True when non-empty [file] is not a plaintext SQLite file (SQLCipher starts with a random salt). */
    fun isEncrypted(file: File): Boolean = !KeyboardDbMigrator.startsWithMagic(file, 0)
}

/**
 * KEYBOARD-048: encrypts `agentic_keyboard_database` (learned words, next-word pairs, auto-correct
 * rules, writing log, style per app, clipboard history, shortcuts, snippets, commands) with
 * SQLCipher 4. The 256-bit key is random and wrapped by the twin's Keystore mechanism
 * ([KeyboardDbKey], [KeystoreDbKeyWrapper]).
 *
 * **Migration of an existing plaintext database** runs once, on the first open after the update,
 * before Room sees the file, and never loses data:
 *
 *  1. **Recover** from an earlier interrupted run: if the plaintext was already moved aside
 *     (`.kbcrypt-plain`) but the encrypted copy never took its place, the plaintext goes back.
 *     If the swap finished, the leftover plaintext is shredded. A partial `.kbcrypt-tmp` copy
 *     (ciphertext only) is deleted.
 *  2. **Copy**: checkpoint and empty the WAL, then `sqlcipher_export` into `.kbcrypt-tmp`.
 *  3. **Verify**: open the copy with the key, `cipher_integrity_check` + `integrity_check`, and
 *     compare user_version and every table's row count with the source. Any difference stops here.
 *  4. **Swap**: rename plaintext → `.kbcrypt-plain`, copy → database (each rename is atomic,
 *     directory fsynced). The plaintext's `-wal`, `-shm` and `-journal` (already empty) go first.
 *  5. **Shred**: overwrite `.kbcrypt-plain` with zeros, fsync, delete.
 *
 * Any failure before step 4 deletes the copy and keeps the plaintext database working; the next
 * launch tries again. A crash at any point is repaired by step 1. Already encrypted, or a fresh
 * install: no copy at all, SQLCipher opens or creates the file directly.
 *
 * The key is only handed to Room after [KeyboardDbEngine.canOpen] has opened the file with it,
 * because a wrong key looks like corruption and Room's default corruption handling deletes the
 * database. If the key is gone for good, the unreadable file is moved aside (never deleted) and a
 * new database starts; if it's only unavailable right now, this launch runs on an in-memory
 * database and the file is left alone.
 *
 * Residue: flash storage doesn't overwrite in place, so the zeros written in step 5 (and
 * `secure_delete`) may land on new blocks while the old plaintext blocks wait for the flash
 * controller to erase them. They are still covered by Android's file-based encryption.
 */
class KeyboardDbMigrator(
    private val key: KeyboardDbKey,
    private val engine: KeyboardDbEngine,
    private val now: () -> Long = System::currentTimeMillis
) {
    enum class Kind { MISSING, PLAINTEXT, ENCRYPTED }

    sealed interface Outcome {
        /** Open with SQLCipher and [sqlKey]. [migrated] when this run converted a plaintext file. */
        class Encrypted(val sqlKey: ByteArray, val migrated: Boolean) : Outcome

        /** The plaintext database stays in use this launch; migration is retried next launch. */
        data class Plaintext(val reason: String) : Outcome

        /** The encrypted file can't be opened right now: run in memory, leave the file untouched. */
        data class InMemory(val reason: String) : Outcome
    }

    fun prepare(main: File): Outcome {
        val engineOk = runCatching { engine.load() }.isSuccess
        recover(main)
        val kind = kind(main)
        if (kind == Kind.MISSING) companions(main).forEach(::shred)
        if (!engineOk) return if (kind == Kind.ENCRYPTED) Outcome.InMemory("engine") else Outcome.Plaintext("engine")
        return when (kind) {
            Kind.MISSING -> keyForNewDatabase()?.let { Outcome.Encrypted(it, migrated = false) } ?: Outcome.Plaintext("key")
            Kind.ENCRYPTED -> openEncrypted(main)
            Kind.PLAINTEXT -> migrate(main)
        }
    }

    private fun openEncrypted(main: File): Outcome {
        val sqlKey = when (val r = key.read()) {
            is KeyboardDbKey.Read.Ok -> r.sqlKey
            KeyboardDbKey.Read.Unavailable -> return Outcome.InMemory("key")
            KeyboardDbKey.Read.Lost -> null
        }
        if (sqlKey != null && engine.canOpen(main, sqlKey)) return Outcome.Encrypted(sqlKey, migrated = false)
        // The key can't open this file and never will (lost key, or a damaged file). Keep the
        // file, out of the way, and start a new database so the keyboard keeps working.
        setAside(main)
        return (sqlKey ?: runCatching { key.create() }.getOrNull())
            ?.let { Outcome.Encrypted(it, migrated = false) } ?: Outcome.InMemory("key")
    }

    private fun migrate(main: File): Outcome {
        val tmp = sibling(main, TMP)
        val old = sibling(main, OLD)
        val sqlKey = keyForNewDatabase() ?: return Outcome.Plaintext("key")
        try {
            listOf(tmp).plus(companions(tmp)).forEach { it.delete() }
            val source = engine.exportEncrypted(main, tmp, sqlKey)
            val copy = engine.verify(tmp, sqlKey)
            check(copy == source) { "copy differs" }
            check(dataSidecars(main).none { it.length() > 0 }) { "WAL not empty" }
            fsync(tmp)
        } catch (t: Throwable) {
            listOf(tmp).plus(companions(tmp)).forEach { it.delete() }
            return Outcome.Plaintext("copy")
        }
        try {
            check(main.renameTo(old)) { "rename" }
            syncDir(main.parentFile)
            companions(main).forEach(::shred)
            check(tmp.renameTo(main)) { "rename" }
            syncDir(main.parentFile)
        } catch (t: Throwable) {
            recover(main)
            return if (kind(main) == Kind.ENCRYPTED) Outcome.Encrypted(sqlKey, migrated = true) else Outcome.Plaintext("swap")
        }
        shredAll(old)
        return Outcome.Encrypted(sqlKey, migrated = true)
    }

    /**
     * What [main] holds. A plaintext SQLite file starts with "SQLite format 3"; a SQLCipher file
     * starts with a random salt. In WAL mode a new database can still be 0 bytes with its first
     * pages in `-wal`, so then the first WAL frame (page 1) is checked instead.
     */
    fun kind(main: File): Kind {
        if (!main.isFile) return Kind.MISSING
        if (main.length() > 0) return if (engine.isEncrypted(main)) Kind.ENCRYPTED else Kind.PLAINTEXT
        val wal = File(main.path + "-wal")
        if (wal.length() < WAL_HEADER + FRAME_HEADER + MAGIC.size) return Kind.MISSING
        return if (startsWithMagic(wal, (WAL_HEADER + FRAME_HEADER).toLong())) Kind.PLAINTEXT else Kind.ENCRYPTED
    }

    /** Finishes or rolls back a swap a crash interrupted, and clears partial copies. Idempotent. */
    internal fun recover(main: File) {
        val tmp = sibling(main, TMP)
        val old = sibling(main, OLD)
        if (old.exists()) {
            if (!main.exists()) {
                // Crashed between the two renames: put the plaintext back; the copy is redone.
                companions(main).forEach(::shred)
                if (old.renameTo(main)) syncDir(main.parentFile)
            } else {
                // The swap finished (or main is newer than the moved-aside copy): shred the old plaintext.
                shredAll(old)
            }
        }
        listOf(tmp).plus(companions(tmp)).forEach { it.delete() }
    }

    /** The current key when one opens, otherwise a new one (only safe when no encrypted file needs the old key). */
    private fun keyForNewDatabase(): ByteArray? =
        (key.read() as? KeyboardDbKey.Read.Ok)?.sqlKey ?: runCatching { key.create() }.getOrNull()

    private fun setAside(main: File) {
        val stamp = now()
        val aside = File(main.path + ".$UNREADABLE-$stamp")
        if (main.renameTo(aside)) {
            companions(main).forEach { f -> f.renameTo(File(aside.path + f.name.removePrefix(main.name))) }
            syncDir(main.parentFile)
        }
    }

    companion object {
        internal const val TMP = "kbcrypt-tmp"
        internal const val OLD = "kbcrypt-plain"
        internal const val UNREADABLE = "kbcrypt-unreadable"
        internal val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
        internal const val WAL_HEADER = 32
        internal const val FRAME_HEADER = 24

        fun sibling(main: File, suffix: String) = File(main.path + ".$suffix")
        fun companions(db: File) = listOf("-wal", "-shm", "-journal").map { File(db.path + it) }

        /** The side files that can hold row data (`-shm` is only the WAL's index). */
        private fun dataSidecars(db: File) = listOf("-wal", "-journal").map { File(db.path + it) }

        internal fun startsWithMagic(file: File, offset: Long): Boolean = runCatching {
            RandomAccessFile(file, "r").use { f ->
                if (f.length() < offset + MAGIC.size) return false
                val head = ByteArray(MAGIC.size)
                f.seek(offset)
                f.readFully(head)
                head.contentEquals(MAGIC)
            }
        }.getOrDefault(false)

        /** Overwrites [file] with zeros, syncs and deletes it. Best effort; see the residue note above. */
        fun shred(file: File) {
            if (!file.exists()) return
            runCatching {
                RandomAccessFile(file, "rw").use { f ->
                    val zeros = ByteArray(64 * 1024)
                    var left = f.length()
                    f.seek(0)
                    while (left > 0) {
                        val n = minOf(left, zeros.size.toLong()).toInt()
                        f.write(zeros, 0, n)
                        left -= n
                    }
                    f.fd.sync()
                }
            }
            file.delete()
        }

        internal fun shredAll(db: File) {
            shred(db)
            companions(db).forEach(::shred)
            syncDir(db.parentFile)
        }

        private fun fsync(file: File) = RandomAccessFile(file, "rw").use { it.fd.sync() }
    }
}

/** Where the last open landed, for tests and the learned-data screen. */
data class KeyboardDbStatus(val state: String, val migrated: Boolean, val prepareMillis: Long)

/**
 * Room's open-helper factory for the keyboard database. Nothing happens at `build()`: the
 * migration and the Keystore unwrap run on the first real open, which Room does off the main
 * thread, and then the matching helper (SQLCipher, plaintext or in-memory) takes over.
 */
class KeyboardDbOpenHelperFactory(
    private val migrator: (Context) -> KeyboardDbMigrator,
    private val engine: KeyboardDbEngine,
    private val onStatus: (KeyboardDbStatus) -> Unit = {}
) : SupportSQLiteOpenHelper.Factory {

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        LazyHelper(configuration)

    private inner class LazyHelper(private val config: SupportSQLiteOpenHelper.Configuration) : SupportSQLiteOpenHelper {
        private var delegate: SupportSQLiteOpenHelper? = null
        private var wal: Boolean? = null

        override val databaseName: String? get() = config.name

        @Synchronized
        override fun setWriteAheadLoggingEnabled(enabled: Boolean) {
            wal = enabled
            delegate?.setWriteAheadLoggingEnabled(enabled)
        }

        override val writableDatabase: SupportSQLiteDatabase get() = helper().writableDatabase
        override val readableDatabase: SupportSQLiteDatabase get() = helper().readableDatabase

        @Synchronized
        override fun close() {
            delegate?.close()
        }

        @Synchronized
        private fun helper(): SupportSQLiteOpenHelper = delegate ?: open().also { h ->
            wal?.let(h::setWriteAheadLoggingEnabled)
            delegate = h
        }

        private fun open(): SupportSQLiteOpenHelper {
            val name = config.name ?: return FrameworkSQLiteOpenHelperFactory().create(config)
            val started = SystemClock.elapsedRealtime()
            val outcome = migrator(config.context).prepare(config.context.getDatabasePath(name))
            val ms = SystemClock.elapsedRealtime() - started
            return when (outcome) {
                is KeyboardDbMigrator.Outcome.Encrypted -> {
                    onStatus(KeyboardDbStatus("encrypted", outcome.migrated, ms))
                    engine.factory(outcome.sqlKey).create(config)
                }
                is KeyboardDbMigrator.Outcome.Plaintext -> {
                    onStatus(KeyboardDbStatus("plaintext:${outcome.reason}", false, ms))
                    FrameworkSQLiteOpenHelperFactory().create(config)
                }
                is KeyboardDbMigrator.Outcome.InMemory -> {
                    onStatus(KeyboardDbStatus("memory:${outcome.reason}", false, ms))
                    FrameworkSQLiteOpenHelperFactory().create(
                        SupportSQLiteOpenHelper.Configuration.builder(config.context)
                            .name(null).callback(config.callback).noBackupDirectory(config.useNoBackupDirectory).build()
                    )
                }
            }
        }
    }
}
