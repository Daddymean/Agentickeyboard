package io.github.daddymean.agentickeyboard.db

import android.content.ContentValues
import android.content.Context
import android.os.SystemClock
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** A copy of a database's shape: user_version, row counts (including sqlite_sequence) and schema size. */
data class DbSnapshot(val userVersion: Int, val counts: Map<String, Long>, val schemaEntries: Int = 0)

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

    /** Opens encrypted [file] with [sqlKey], runs the full integrity checks and returns its snapshot. Throws on any failure. */
    fun verify(file: File, sqlKey: ByteArray): DbSnapshot

    /**
     * Integrity probe used on every open. Never throws: a non-ok check or a corrupt / malformed
     * image is [IntegrityProbe.Corrupt]; disk I/O, busy, and OOM are [IntegrityProbe.Transient].
     * [quick] uses `PRAGMA quick_check`; a full `cipher_integrity_check` + `integrity_check` is
     * for migration and a background pass, not the IME start path.
     */
    fun integrity(file: File, sqlKey: ByteArray, quick: Boolean): IntegrityProbe

    /**
     * Integrity probe for a plaintext SQLite file (empty key). Never uses the SQLCipher key,
     * so "file is not a database" cannot be a false positive on a still-plaintext main.
     */
    fun integrityPlaintext(file: File, quick: Boolean): IntegrityProbe

    /** Snapshot of a plaintext SQLite file (empty key). */
    fun snapshotPlaintext(file: File): DbSnapshot

    /** Probe that [sqlKey] opens [file]. Must never delete or change the file, even when it fails. */
    fun canOpen(file: File, sqlKey: ByteArray): OpenProbe

    fun factory(sqlKey: ByteArray, onCorruption: (File) -> Unit = {}): SupportSQLiteOpenHelper.Factory

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
 *     If the swap finished, the leftover plaintext is shredded only after the new file verifies
 *     (integrity check plus row counts and schema size). A partial `.kbcrypt-tmp` copy
 *     (ciphertext only) is deleted.
 *  2. **Copy**: checkpoint and empty the WAL, then `sqlcipher_export` into `.kbcrypt-tmp`.
 *  3. **Verify**: open the copy with the key, `cipher_integrity_check` + `integrity_check`, and
 *     compare user_version, every table's row count and the sqlite_master entry count with the
 *     source. Any difference stops here.
 *  4. **Swap**: rename plaintext → `.kbcrypt-plain`, copy → database (each rename is atomic,
 *     directory fsynced). The plaintext's `-wal`, `-shm` and `-journal` (already empty) go first.
 *  5. **Shred**: overwrite `.kbcrypt-plain` with zeros, fsync, delete.
 *
 * Any failure before step 4 deletes the copy and keeps the plaintext database working; the next
 * launch retries after a backoff. A crash at any point is repaired by step 1. Already encrypted,
 * or a fresh install: no copy at all, SQLCipher opens or creates the file directly.
 *
 * The key is only handed to Room after [KeyboardDbEngine.canOpen] has opened the file with it,
 * and only a permanent failure (not a transient I/O error) moves the file aside. If the key is
 * gone for good, the unreadable file and the old wrapped-key file are moved aside as a pair
 * (never deleted while still readable) and a new database starts; if it's only unavailable
 * right now, this launch runs on an in-memory database with writes blocked, the file is left
 * alone, and the next launch retries. After several unavailable launches (counted once per
 * process), the learned-data screen offers a one-tap reset. The newest 3 asides are kept
 * together; an aside that is still readable is never shredded.
 *
 * A damaged page during an open only flags the file when the integrity check itself returns
 * a non-ok result (or SQLite reports a corrupt / malformed image). A disk I/O error is not
 * treated as corruption. The next [prepare] re-checks before moving anything, so a one-shot
 * failure cannot set a healthy file aside. In-use connections never see a renamed inode.
 *
 * Residue: flash storage doesn't overwrite in place, so the zeros written in step 5 (and
 * `secure_delete`) may land on new blocks while the old plaintext blocks wait for the flash
 * controller to erase them. They are still covered by Android's file-based encryption. The
 * SQLCipher raw key is held in memory for the process lifetime so pooled connections can
 * reopen; `cipher_memory_security` is left off (a keyboard can't pay that cost on every key).
 */
class KeyboardDbMigrator(
    private val key: KeyboardDbKey,
    private val engine: KeyboardDbEngine,
    private val now: () -> Long = System::currentTimeMillis,
    internal val health: KeyboardDbHealth = KeyboardDbHealth(File(System.getProperty("java.io.tmpdir"), "kbdb-health"), now),
) {
    enum class Kind { MISSING, PLAINTEXT, ENCRYPTED }

    sealed interface Outcome {
        /** Open with SQLCipher and [sqlKey]. [migrated] when this run converted a plaintext file. */
        class Encrypted(val sqlKey: ByteArray, val migrated: Boolean) : Outcome

        /** The plaintext database stays in use this launch; migration is retried after backoff. */
        data class Plaintext(val reason: String) : Outcome

        /** The encrypted file can't be opened right now: in-memory, writes blocked, file untouched. */
        data class InMemory(val reason: String) : Outcome
    }

    fun prepare(main: File): Outcome {
        val engineOk = runCatching { engine.load() }.isSuccess
        val recovered = recover(main)
        if (recovered == Recovered.Stuck) {
            health.recordMigrationFailure("recover")
            return Outcome.InMemory("recover")
        }
        // Re-check a previous flag before moving: a one-shot I/O error must not set a healthy file aside.
        // The marker is deleted only after a successful move (or when the file now checks out).
        if (engineOk) confirmPendingCorruption(main)
        val kind = kind(main)
        if (kind == Kind.MISSING) companions(main).forEach(::shred)
        if (!engineOk) {
            return if (kind == Kind.ENCRYPTED) {
                health.recordMigrationFailure("engine")
                Outcome.InMemory("engine")
            } else {
                health.recordMigrationFailure("engine")
                Outcome.Plaintext("engine")
            }
        }
        return when (kind) {
            Kind.MISSING -> keyForNewDatabase(main)?.let {
                health.recordSuccess()
                Outcome.Encrypted(it, migrated = false)
            } ?: Outcome.Plaintext("key")
            Kind.ENCRYPTED -> openEncrypted(main)
            Kind.PLAINTEXT -> migrate(main)
        }
    }

    private fun openEncrypted(main: File): Outcome {
        val sqlKey = when (val r = key.read()) {
            is KeyboardDbKey.Read.Ok -> r.sqlKey
            KeyboardDbKey.Read.Unavailable -> {
                health.recordUnavailable()
                return Outcome.InMemory("key")
            }
            KeyboardDbKey.Read.Lost -> null
        }
        if (sqlKey != null) {
            when (engine.canOpen(main, sqlKey)) {
                OpenProbe.Ok -> {
                    when (engine.integrity(main, sqlKey, quick = true)) {
                        IntegrityProbe.Corrupt -> {
                            // Flag only this launch. The next prepare must see the failure again before moving.
                            flagCorruption(main)
                        }
                        IntegrityProbe.Transient -> Unit
                        IntegrityProbe.Ok -> maybeScheduleFullIntegrity(main, sqlKey)
                    }
                    health.recordSuccess()
                    return Outcome.Encrypted(sqlKey, migrated = false)
                }
                OpenProbe.Transient -> return Outcome.InMemory("transient")
                OpenProbe.Unopenable -> Unit
            }
        }
        val stamp = now()
        setAside(main, stamp)
        val newKey = if (sqlKey != null) {
            key.copyAside(stamp)
            sqlKey
        } else {
            key.moveAside(stamp)
            runCatching { key.create() }.getOrNull()
        }
        capPairedAsides(main)
        if (!main.isFile) runCatching { sibling(main, CORRUPT).delete() }
        return newKey?.let {
            health.recordSuccess()
            Outcome.Encrypted(it, migrated = false)
        } ?: Outcome.InMemory("key")
    }

    private fun migrate(main: File): Outcome {
        if (!health.shouldRetryMigration()) return Outcome.Plaintext("backoff")
        val tmp = sibling(main, TMP)
        val old = sibling(main, OLD)
        val sqlKey = keyForNewDatabase(main) ?: return Outcome.Plaintext("key").also { health.recordMigrationFailure("key") }
        try {
            listOf(tmp).plus(companions(tmp)).forEach { it.delete() }
            val source = engine.exportEncrypted(main, tmp, sqlKey)
            val copy = engine.verify(tmp, sqlKey)
            check(copy == source) { "copy differs" }
            check(dataSidecars(main).none { it.length() > 0 }) { "WAL not empty" }
            fsync(tmp)
        } catch (t: Throwable) {
            listOf(tmp).plus(companions(tmp)).forEach { it.delete() }
            health.recordMigrationFailure("copy")
            return Outcome.Plaintext("copy")
        }
        try {
            check(main.renameTo(old)) { "rename" }
            syncDir(main.parentFile)
            companions(main).forEach(::shred)
            check(tmp.renameTo(main)) { "rename" }
            syncDir(main.parentFile)
        } catch (t: Throwable) {
            val recovered = recover(main)
            if (recovered == Recovered.Swapped || kind(main) == Kind.ENCRYPTED && validReplacement(main, old)) {
                beginShred(main, old)
                health.recordSuccess()
                return Outcome.Encrypted(sqlKey, migrated = true)
            }
            health.recordMigrationFailure("swap")
            return if (recovered == Recovered.Stuck) Outcome.InMemory("swap") else Outcome.Plaintext("swap")
        }
        beginShred(main, old)
        health.recordSuccess()
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

    enum class Recovered { Ok, Swapped, Stuck }

    /**
     * Finishes or rolls back a swap a crash interrupted, and clears partial copies. Idempotent.
     * A verified encrypted [main] is always kept. The leftover plaintext is shredded only after
     * that, or when the leftover no longer opens as plaintext (a kill in the middle of the
     * zero pass). A file that is not a plaintext database is never restored over a good copy.
     */
    internal fun recover(main: File): Recovered {
        val tmp = sibling(main, TMP)
        val old = sibling(main, OLD)
        val shredding = sibling(main, SHREDDING)
        var result = Recovered.Ok
        if (shredding.exists()) {
            if (verifiedEncrypted(main)) {
                shredAll(shredding)
                result = Recovered.Swapped
            } else if (!main.exists() && opensAsPlaintext(shredding)) {
                result = if (shredding.renameTo(main)) {
                    syncDir(main.parentFile)
                    Recovered.Ok
                } else {
                    Recovered.Stuck
                }
            } else if (opensAsPlaintext(shredding) && !verifiedEncrypted(main) && main.exists()) {
                // Should be rare after a swap. Keep the readable leftover; don't put it back
                // over an encrypted main we couldn't verify this launch.
                result = Recovered.Ok
            } else {
                // Half-zeroed leftover, and no verified main: do not restore it.
                result = if (main.exists()) Recovered.Ok else Recovered.Stuck
            }
        }
        if (old.exists()) {
            if (!main.exists()) {
                if (opensAsPlaintext(old)) {
                    companions(main).forEach(::shred)
                    result = if (old.renameTo(main)) {
                        syncDir(main.parentFile)
                        Recovered.Ok
                    } else {
                        Recovered.Stuck
                    }
                } else {
                    result = Recovered.Stuck
                }
            } else if (verifiedEncrypted(main)) {
                if (!opensAsPlaintext(old) || snapshotsMatch(main, old)) {
                    beginShred(main, old)
                    result = Recovered.Swapped
                } else {
                    // main verifies but is not a complete copy of a still-readable plaintext:
                    // get the plaintext back in place (M3).
                    val junk = File(main.path + ".$UNCONFIRMED-${now()}")
                    companions(main).forEach { f -> f.renameTo(File(junk.path + f.name.removePrefix(main.name))) }
                    result = if (main.renameTo(junk) && old.renameTo(main)) {
                        syncDir(main.parentFile)
                        capPairedAsides(main)
                        Recovered.Ok
                    } else {
                        Recovered.Stuck
                    }
                }
            } else if (opensAsPlaintext(old)) {
                val junk = File(main.path + ".$UNCONFIRMED-${now()}")
                companions(main).forEach { f -> f.renameTo(File(junk.path + f.name.removePrefix(main.name))) }
                result = if (main.renameTo(junk) && old.renameTo(main)) {
                    syncDir(main.parentFile)
                    capPairedAsides(main)
                    Recovered.Ok
                } else {
                    Recovered.Stuck
                }
            } else {
                // Don't restore a zeroed leftover over whatever is in main.
                result = Recovered.Ok
            }
        }
        listOf(tmp).plus(companions(tmp)).forEach { it.delete() }
        return result
    }

    /** True when [file] still opens as a plaintext SQLite database (header intact). */
    internal fun opensAsPlaintext(file: File): Boolean =
        file.isFile && file.length() > 0 && startsWithMagic(file, 0)

    /** [main] opens with the current key and passes the integrity checks. */
    internal fun verifiedEncrypted(main: File): Boolean {
        val sqlKey = (key.read() as? KeyboardDbKey.Read.Ok)?.sqlKey ?: return false
        if (kind(main) != Kind.ENCRYPTED) return false
        return runCatching { engine.verify(main, sqlKey) }.isSuccess
    }

    private fun snapshotsMatch(main: File, old: File): Boolean {
        val sqlKey = (key.read() as? KeyboardDbKey.Read.Ok)?.sqlKey ?: return false
        val copy = runCatching { engine.verify(main, sqlKey) }.getOrNull() ?: return false
        val source = runCatching { engine.snapshotPlaintext(old) }.getOrNull() ?: return false
        return copy == source
    }

    /** Integrity check plus row counts and schema size: [main] is a complete encrypted copy of [old]. */
    internal fun validReplacement(main: File, old: File): Boolean =
        verifiedEncrypted(main) && opensAsPlaintext(old) && snapshotsMatch(main, old)

    /**
     * Durably marks [old] as "shredding started" (rename + dir fsync) before any zero pass, so a
     * kill mid-shred can never be mistaken for "restore the leftover plaintext".
     */
    internal fun beginShred(main: File, old: File) {
        val dest = sibling(main, SHREDDING)
        if (old.exists() && old.absolutePath != dest.absolutePath) {
            companions(old).forEach { f ->
                f.renameTo(File(dest.path + f.name.removePrefix(old.name)))
            }
            if (!old.renameTo(dest)) {
                shredAll(old)
                return
            }
            syncDir(main.parentFile)
        }
        shredAll(dest)
        if (old.exists() && old.absolutePath != dest.absolutePath) shredAll(old)
    }

    /** The current key when one opens, otherwise a new one (only safe when no encrypted file needs the old key). */
    private fun keyForNewDatabase(main: File): ByteArray? = when (val r = key.read()) {
        is KeyboardDbKey.Read.Ok -> r.sqlKey
        KeyboardDbKey.Read.Lost -> {
            key.moveAside(now())
            capPairedAsides(main)
            runCatching { key.create() }.getOrNull()
        }
        KeyboardDbKey.Read.Unavailable -> null
    }

    /** Renames [main] and its side files to `.kbcrypt-unreadable-<stamp>`. Does not cap. */
    fun setAside(main: File, stamp: Long = now()): Boolean {
        if (!main.isFile) return false
        val aside = File(main.path + ".$UNREADABLE-$stamp")
        if (!main.renameTo(aside)) return false
        companions(main).forEach { f -> f.renameTo(File(aside.path + f.name.removePrefix(main.name))) }
        syncDir(main.parentFile)
        return true
    }

    /** Writes a marker so the next [prepare] moves [file] aside before opening anything. */
    fun flagCorruption(file: File) {
        if (!file.isFile) return
        val marker = sibling(file, CORRUPT)
        runCatching {
            marker.writeText("1")
            syncDir(file.parentFile)
        }
    }

    /**
     * If a previous launch flagged corruption, re-run the cheap integrity probe. Move the file
     * aside only when the failure repeats. The marker is deleted after a successful move, or
     * when the file now checks out. A transient I/O error leaves the marker for the next launch.
     */
    internal fun confirmPendingCorruption(main: File) {
        val marker = sibling(main, CORRUPT)
        if (!marker.isFile) return
        if (!main.isFile) {
            runCatching { marker.delete() }
            return
        }
        // Kind first: a still-plaintext file (failed migration / backoff) must be
        // re-checked with an empty key via integrityPlaintext. Opening it with the
        // SQLCipher key would fail with "not a database" even when the file is healthy.
        // After the empty-key probe, "not a database" means the plaintext file is
        // actually damaged, so that result is correctly Corrupt.
        val probe = when (kind(main)) {
            Kind.MISSING -> {
                runCatching { marker.delete() }
                return
            }
            Kind.PLAINTEXT -> engine.integrityPlaintext(main, quick = true)
            Kind.ENCRYPTED -> {
                val sqlKey = (key.read() as? KeyboardDbKey.Read.Ok)?.sqlKey ?: return
                engine.integrity(main, sqlKey, quick = true)
            }
        }
        when (probe) {
            IntegrityProbe.Ok -> runCatching { marker.delete() }
            IntegrityProbe.Transient -> Unit
            IntegrityProbe.Corrupt -> {
                val stamp = now()
                if (setAside(main, stamp)) {
                    key.copyAside(stamp)
                    capPairedAsides(main)
                    runCatching { marker.delete() }
                }
            }
        }
    }

    /** @deprecated Kept for tests that still call the old name; use [confirmPendingCorruption]. */
    internal fun consumeCorruptMarker(main: File): Boolean {
        val marker = sibling(main, CORRUPT)
        if (!marker.isFile) return false
        confirmPendingCorruption(main)
        return !marker.isFile && !main.isFile
    }

    private fun maybeScheduleFullIntegrity(main: File, sqlKey: ByteArray) {
        if (!scheduleFullIntegrity) return
        synchronized(lock) {
            if (fullIntegrityScheduledThisProcess) return
            fullIntegrityScheduledThisProcess = true
        }
        val keyCopy = sqlKey.copyOf()
        fullIntegrityExecutor.execute {
            try {
                if (engine.integrity(main, keyCopy, quick = false) == IntegrityProbe.Corrupt) {
                    flagCorruption(main)
                }
            } finally {
                keyCopy.fill(0)
            }
        }
    }

    /** True when a leftover `.kbcrypt-plain` or any set-aside copy is still on the phone. */
    fun hasKeptAsides(main: File): Boolean = keptAsideCount(main) > 0

    fun keptAsideCount(main: File): Int = listKeptCopies(main).size

    /** Set-aside copies, including a leftover `.kbcrypt-plain` only when [main] is verified encrypted. */
    fun listKeptCopies(main: File): List<KeptCopy> =
        listKeptCopies(main, key.file, includeLeftoverPlain = leftoverPlaintextExists(main))

    /** True when a leftover plaintext sits next to a verified encrypted [main]. */
    fun leftoverPlaintextExists(main: File): Boolean =
        sibling(main, OLD).isFile && verifiedEncrypted(main)

    /**
     * Securely deletes set-aside DB/key units and leftover plaintext that is not the only
     * remaining copy. Never touches the live database, the live key, a mid-migration
     * `.kbcrypt-tmp`, a shredding leftover, or a `.kbcrypt-plain` unless [main] is a
     * verified encrypted database (a leftover next to an unverified main may be the
     * only good copy after a failed recover). Returns true when at least one file was removed.
     */
    fun shredKeptAsides(main: File): Boolean {
        val liveDb = runCatching { main.canonicalFile }.getOrDefault(main)
        val liveKey = runCatching { key.file.canonicalFile }.getOrDefault(key.file)
        val copies = listKeptCopies(main)
        var removed = false
        fun safe(file: File, live: File, liveName: String): Boolean {
            val canon = runCatching { file.canonicalFile }.getOrDefault(file)
            return canon != live && file.name != liveName
        }
        for (c in copies) {
            c.db?.let { f ->
                if (safe(f, liveDb, main.name)) {
                    // A main aside or leftover is shredded with its sidecars; an orphan
                    // sidecar is shredded on its own (shredAll would look for the wrong names).
                    if (c.orphanSidecar) shred(f) else shredAll(f)
                    removed = true
                }
            }
            for (s in c.sides) {
                if (safe(s, liveDb, main.name)) {
                    shred(s)
                    removed = true
                }
            }
            c.keyAside?.let { f ->
                if (safe(f, liveKey, key.file.name)) {
                    shred(f)
                    removed = true
                }
            }
        }
        // Orphan .lost-* keys are not counted as copies, but they are leftover key
        // material and are cleaned up with a confirmed delete of older copies.
        val lostKey = Regex("^${Regex.escape(key.file.name)}\\.lost-(\\d+)$")
        key.file.parentFile?.listFiles()?.forEach { f ->
            if (lostKey.matches(f.name) && safe(f, liveKey, key.file.name)) {
                shred(f)
                removed = true
            }
        }
        return removed
    }

    /** Moves the live DB and its wrapped key aside under one stamp and starts a new encrypted database. */
    fun resetBroken(main: File): Outcome {
        val stamp = now()
        setAside(main, stamp)
        key.moveAside(stamp)
        capPairedAsides(main)
        health.clear()
        val sqlKey = runCatching { key.create() }.getOrNull() ?: return Outcome.InMemory("key")
        return Outcome.Encrypted(sqlKey, migrated = false)
    }

    /**
     * Keeps all asides that are still readable, and the newest [KeyboardDbHealth.ASIDE_CAP]
     * that are not. Database and key asides share a stamp and are kept or shredded together.
     * Unpaired asides (no matching key) are shredded first. A still-readable aside, including
     * a leftover plaintext `.kbcrypt-unconfirmed-*`, is never shredded.
     */
    internal fun capPairedAsides(main: File, cap: Int = KeyboardDbHealth.ASIDE_CAP) {
        val dbDir = main.parentFile
        val keyFile = key.file
        val keyDir = keyFile.parentFile
        val unreadable = Regex("^${Regex.escape(main.name)}\\.${UNREADABLE}-(\\d+)$")
        val unconfirmed = Regex("^${Regex.escape(main.name)}\\.${UNCONFIRMED}-(\\d+)$")
        val lostKey = Regex("^${Regex.escape(keyFile.name)}\\.lost-(\\d+)$")

        data class Pair(
            val stamp: String,
            val db: File?,
            val keyAside: File?,
            val plaintext: Boolean,
            val stampValue: Long,
        )

        val dbByStamp = mutableMapOf<String, Pair>()
        fun putDb(stamp: String, file: File, plaintext: Boolean) {
            val stampValue = stamp.toLongOrNull() ?: file.lastModified()
            val prev = dbByStamp[stamp]
            dbByStamp[stamp] = Pair(stamp, file, prev?.keyAside, plaintext, stampValue)
        }
        dbDir?.listFiles()?.forEach { f ->
            unreadable.matchEntire(f.name)?.let { putDb(it.groupValues[1], f, plaintext = false) }
            unconfirmed.matchEntire(f.name)?.let { putDb(it.groupValues[1], f, plaintext = true) }
        }
        keyDir?.listFiles()?.forEach { f ->
            val m = lostKey.matchEntire(f.name) ?: return@forEach
            val stamp = m.groupValues[1]
            val stampValue = stamp.toLongOrNull() ?: f.lastModified()
            val prev = dbByStamp[stamp]
            dbByStamp[stamp] = Pair(stamp, prev?.db, f, prev?.plaintext == true, stampValue)
        }
        val pairs = dbByStamp.values.toList()
        // Under the cap there is nothing to shred, so skip every readability probe (L3).
        if (pairs.size <= cap) return
        fun readable(p: Pair): Boolean {
            if (p.db == null) return false
            // Plaintext leftovers are still user data (L4). Anything else is only kept if a
            // matching key still opens it; a random unreadable file is not treated as readable.
            if (p.plaintext || startsWithMagic(p.db, 0)) return true
            val keysToTry = buildList {
                p.keyAside?.let { add(it) }
                if (key.file.isFile) add(key.file)
            }
            for (kf in keysToTry) {
                when (val r = key.readAside(kf)) {
                    is KeyboardDbKey.Read.Ok -> {
                        val probe = engine.canOpen(p.db, r.sqlKey)
                        if (probe != OpenProbe.Unopenable) return true
                    }
                    KeyboardDbKey.Read.Unavailable -> return true // might be readable; don't shred
                    KeyboardDbKey.Read.Lost -> Unit
                }
            }
            return false
        }
        val keepReadable = pairs.filter(::readable)
        val shreddable = pairs.filter { it !in keepReadable }
        val (unpaired, paired) = shreddable.partition { it.db == null || it.keyAside == null }
        val keepBudget = (cap - keepReadable.size).coerceAtLeast(0)
        val keepShreddable = (paired.sortedByDescending { it.stampValue } +
            unpaired.sortedByDescending { it.stampValue }).take(keepBudget)
        val toShred = shreddable.filter { it !in keepShreddable }
        for (p in toShred) {
            p.db?.let { shredAll(it) }
            p.keyAside?.let { shred(it) }
        }
    }

    companion object {
        private val lock = Any()
        @Volatile internal var scheduleFullIntegrity: Boolean = true
        @Volatile internal var fullIntegrityScheduledThisProcess: Boolean = false
        internal var fullIntegrityExecutor: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "kb-db-integrity").apply { isDaemon = true }
        }

        internal const val TMP = "kbcrypt-tmp"
        internal const val OLD = "kbcrypt-plain"
        internal const val UNREADABLE = "kbcrypt-unreadable"
        internal const val UNCONFIRMED = "kbcrypt-unconfirmed"
        internal const val CORRUPT = "kbcrypt-corrupt"
        internal const val SHREDDING = "kbcrypt-shredding"
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

        data class KeptCopy(
            val db: File?,
            val keyAside: File?,
            val sides: List<File> = emptyList(),
            val orphanSidecar: Boolean = false,
        )

        /**
         * Set-aside DB units plus, when [includeLeftoverPlain] is true, a leftover
         * `.kbcrypt-plain`. Callers that can verify [main] should pass that result;
         * a leftover next to an unverified main may be the only good copy (`recover`
         * returned Stuck) and must not be listed or shredded.
         * Unreadable and unconfirmed asides with the same stamp are separate entries.
         * Orphan `-wal`/`-shm`/`-journal` side files with no aside are listed.
         * A `.lost-*` key with no matching DB is not counted as a copy.
         */
        fun listKeptCopies(main: File, keyFile: File, includeLeftoverPlain: Boolean = false): List<KeptCopy> {
            val out = mutableListOf<KeptCopy>()
            val leftover = sibling(main, OLD)
            if (leftover.isFile && includeLeftoverPlain) out += KeptCopy(leftover, null)
            val dbDir = main.parentFile
            val keyDir = keyFile.parentFile
            val dbAside = Regex("^${Regex.escape(main.name)}\\.(${UNREADABLE}|${UNCONFIRMED})-(\\d+)$")
            val dbSide = Regex("^${Regex.escape(main.name)}\\.(${UNREADABLE}|${UNCONFIRMED})-(\\d+)(-wal|-shm|-journal)$")
            val lostKey = Regex("^${Regex.escape(keyFile.name)}\\.lost-(\\d+)$")
            data class Id(val kind: String, val stamp: String)
            val byId = linkedMapOf<Id, KeptCopy>()
            dbDir?.listFiles()?.forEach { f ->
                dbAside.matchEntire(f.name)?.let { m ->
                    val id = Id(m.groupValues[1], m.groupValues[2])
                    val prev = byId[id]
                    byId[id] = KeptCopy(f, prev?.keyAside, prev?.sides.orEmpty())
                }
                dbSide.matchEntire(f.name)?.let { m ->
                    val id = Id(m.groupValues[1], m.groupValues[2])
                    val prev = byId[id]
                    if (prev?.db != null && !prev.orphanSidecar) {
                        // Main aside already listed; shredAll will take the sidecars.
                    } else {
                        val sides = prev?.sides.orEmpty() + f
                        byId[id] = KeptCopy(prev?.db, prev?.keyAside, sides, orphanSidecar = prev?.db == null)
                    }
                }
            }
            // Attach a matching key to at most one DB unit of the same stamp (prefer unreadable).
            keyDir?.listFiles()?.forEach { f ->
                val m = lostKey.matchEntire(f.name) ?: return@forEach
                val stamp = m.groupValues[1]
                val id = listOf(UNREADABLE, UNCONFIRMED).map { Id(it, stamp) }.firstOrNull { it in byId } ?: return@forEach
                val prev = byId[id]!!
                if (prev.keyAside == null) byId[id] = prev.copy(keyAside = f)
            }
            out += byId.values
            return out
        }

        fun leftoverPlaintextExists(main: File, verifiedEncryptedMain: Boolean): Boolean =
            sibling(main, OLD).isFile && verifiedEncryptedMain


        fun corruptionFlagged(main: File): Boolean = sibling(main, CORRUPT).isFile
    }
}

/** Where the last open landed, for tests and the learned-data screen. */
data class KeyboardDbStatus(
    val state: String,
    val migrated: Boolean,
    val prepareMillis: Long,
    val unavailableCount: Int = 0,
    val migrationFailures: Int = 0,
    val lastFailure: String? = null,
    val keptOlderCount: Int = 0,
    val plaintextLeftover: Boolean = false,
    val corruptionFlagged: Boolean = false,
) {
    val keptOlderCopy: Boolean get() = keptOlderCount > 0
    val kind: String get() = state.substringBefore(':')
    /** True when writes and deletes reach the on-disk database (encrypted or still-plaintext). */
    val writesReachDisk: Boolean get() = kind == "encrypted" || kind == "plaintext"
    /** In-memory fallback. Kept for this process so in-flight readers keep their handle. */
    val ephemeral: Boolean get() = kind == "memory"
    val offerReset: Boolean get() = unavailableCount >= KeyboardDbHealth.RESET_AFTER
}

/** User-visible copy for the learned-data screen. No secrets, no file paths. */
object KeyboardDbUserStatus {
    fun banner(status: KeyboardDbStatus?): String? {
        if (status == null) return null
        val parts = mutableListOf<String>()
        when {
            status.kind == "memory" -> parts +=
                "The keyboard couldn't open its encrypted data this time. Nothing new is being saved to the phone, and deleting here wouldn't change what's stored."
            status.state == "plaintext:backoff" || status.kind == "plaintext" -> parts +=
                "The keyboard is still using the unencrypted database while it retries encryption."
            status.kind != "encrypted" -> parts +=
                "The keyboard's saved data isn't in its usual encrypted state."
        }
        if (status.corruptionFlagged) parts +=
            "The keyboard found a problem in its saved data. If it happens again, an older copy will be kept on the phone. You can delete kept copies from this screen."
        if (status.keptOlderCount > 0) parts +=
            "An older copy of the keyboard's data is still kept on the phone. You can delete it from this screen."
        if (status.plaintextLeftover) parts +=
            "A leftover unencrypted copy from encryption is still on the phone. You can delete older copies from this screen."
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
    }

    fun passportBlocked(status: KeyboardDbStatus?): String? =
        if (status?.ephemeral == true || status?.writesReachDisk == false)
            "The keyboard's encrypted data isn't available, so a passport can't be imported or exported."
        else null

    fun refuseDelete(status: KeyboardDbStatus?): String? =
        if (status?.writesReachDisk == true) null
        else "The keyboard's saved data isn't available right now, so nothing was deleted on the phone."

    fun resetLabel(status: KeyboardDbStatus?): String? =
        if (status?.offerReset == true) "Start a new encrypted database" else null

    const val RESET_EXPLAIN =
        "The unreadable file and its key are moved aside together, not deleted. The newest 3 asides are kept; an aside that is still readable is never removed automatically. You can delete kept older copies from this screen. A new empty encrypted database starts. You can only do this after several failed launches."

    const val DELETE_KEPT_EXPLAIN =
        "This deletes older copies of the keyboard's data kept on the phone. They may hold earlier learned words and corrections. The database the keyboard is using now is not removed. A leftover copy that is the only remaining data is kept."
}

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
            val name = config.name ?: return neverDeleteFramework().create(config)
            val started = SystemClock.elapsedRealtime()
            val migrator = migrator(config.context)
            val dbFile = config.context.getDatabasePath(name)
            val outcome = migrator.prepare(dbFile)
            val ms = SystemClock.elapsedRealtime() - started
            val copies = migrator.listKeptCopies(dbFile)
            val leftover = migrator.leftoverPlaintextExists(dbFile)
            val flagged = KeyboardDbMigrator.corruptionFlagged(dbFile)
            fun report(state: String, migrated: Boolean) = status(
                state, migrated, ms, migrator.health,
                keptOlderCount = copies.size,
                plaintextLeftover = leftover,
                corruptionFlagged = flagged,
            )
            return when (outcome) {
                is KeyboardDbMigrator.Outcome.Encrypted -> {
                    onStatus(report("encrypted", outcome.migrated))
                    engine.factory(outcome.sqlKey) { file -> migrator.flagCorruption(file) }.create(config)
                }
                is KeyboardDbMigrator.Outcome.Plaintext -> {
                    onStatus(report("plaintext:${outcome.reason}", false))
                    neverDeleteFramework { file -> migrator.flagCorruption(file) }.create(config)
                }
                is KeyboardDbMigrator.Outcome.InMemory -> {
                    onStatus(report("memory:${outcome.reason}", false))
                    WriteBlockingOpenHelper(
                        neverDeleteFramework().create(
                            SupportSQLiteOpenHelper.Configuration.builder(config.context)
                                .name(null).callback(config.callback)
                                .noBackupDirectory(config.useNoBackupDirectory)
                                .allowDataLossOnRecovery(config.allowDataLossOnRecovery)
                                .build()
                        )
                    )
                }
            }
        }
    }
}

internal fun status(
    state: String,
    migrated: Boolean,
    ms: Long,
    health: KeyboardDbHealth?,
    keptOlderCount: Int = 0,
    plaintextLeftover: Boolean = false,
    corruptionFlagged: Boolean = false,
) = KeyboardDbStatus(
    state = state,
    migrated = migrated,
    prepareMillis = ms,
    unavailableCount = health?.unavailableCount ?: 0,
    migrationFailures = health?.migrationFailures ?: 0,
    lastFailure = health?.lastReason,
    keptOlderCount = keptOlderCount,
    plaintextLeftover = plaintextLeftover,
    corruptionFlagged = corruptionFlagged,
)

/** Framework factory whose Room callback never deletes or moves the file on corruption; it only flags. */
internal fun neverDeleteFramework(onCorruption: (File) -> Unit = {}): SupportSQLiteOpenHelper.Factory =
    SupportSQLiteOpenHelper.Factory { config ->
        val cb = config.callback
        val wrapped = object : SupportSQLiteOpenHelper.Callback(cb.version) {
            override fun onConfigure(db: SupportSQLiteDatabase) = cb.onConfigure(db)
            override fun onCreate(db: SupportSQLiteDatabase) = cb.onCreate(db)
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = cb.onUpgrade(db, oldVersion, newVersion)
            override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = cb.onDowngrade(db, oldVersion, newVersion)
            override fun onOpen(db: SupportSQLiteDatabase) = cb.onOpen(db)
            override fun onCorruption(db: SupportSQLiteDatabase) {
                val path = db.path ?: return
                if (path.isEmpty() || path == ":memory:") return
                onCorruption(File(path))
            }
        }
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(config.context)
                .name(config.name)
                .callback(wrapped)
                .noBackupDirectory(config.useNoBackupDirectory)
                .allowDataLossOnRecovery(config.allowDataLossOnRecovery)
                .build()
        )
    }

/** An in-memory helper whose writes are no-ops, so nothing is silently "learned" or "deleted". */
internal class WriteBlockingOpenHelper(private val inner: SupportSQLiteOpenHelper) : SupportSQLiteOpenHelper {
    override val databaseName: String? get() = inner.databaseName
    override fun setWriteAheadLoggingEnabled(enabled: Boolean) = inner.setWriteAheadLoggingEnabled(enabled)
    override val writableDatabase: SupportSQLiteDatabase get() = WriteBlockingDatabase(inner.writableDatabase)
    override val readableDatabase: SupportSQLiteDatabase get() = WriteBlockingDatabase(inner.readableDatabase)
    override fun close() = inner.close()
}

internal class WriteBlockingDatabase(private val inner: SupportSQLiteDatabase) : SupportSQLiteDatabase by inner {
    override fun insert(table: String, conflictAlgorithm: Int, values: ContentValues): Long = -1L
    override fun delete(table: String, whereClause: String?, whereArgs: Array<out Any?>?): Int = 0
    override fun update(table: String, conflictAlgorithm: Int, values: ContentValues, whereClause: String?, whereArgs: Array<out Any?>?): Int = 0
    override fun execSQL(sql: String) { if (!isMutatingSql(sql)) inner.execSQL(sql) }
    override fun execSQL(sql: String, bindArgs: Array<out Any?>) { if (!isMutatingSql(sql)) inner.execSQL(sql, bindArgs) }
    override fun compileStatement(sql: String): SupportSQLiteStatement =
        if (isMutatingSql(sql)) NoOpStatement else inner.compileStatement(sql)
}

internal fun isMutatingSql(sql: String): Boolean {
    val head = sql.trimStart().takeWhile { it.isLetter() }.uppercase()
    if (head in setOf("SELECT", "PRAGMA", "BEGIN", "COMMIT", "END", "ROLLBACK", "SAVEPOINT", "RELEASE", "EXPLAIN")) return false
    return Regex("(?is)\\b(INSERT|UPDATE|DELETE|REPLACE|CREATE|DROP|ALTER)\\b").containsMatchIn(sql)
}

private object NoOpStatement : SupportSQLiteStatement {
    override fun execute() = Unit
    override fun executeUpdateDelete() = 0
    override fun executeInsert() = -1L
    override fun simpleQueryForLong() = 0L
    override fun simpleQueryForString(): String? = null
    override fun bindNull(index: Int) = Unit
    override fun bindLong(index: Int, value: Long) = Unit
    override fun bindDouble(index: Int, value: Double) = Unit
    override fun bindString(index: Int, value: String) = Unit
    override fun bindBlob(index: Int, value: ByteArray) = Unit
    override fun clearBindings() = Unit
    override fun close() = Unit
}
