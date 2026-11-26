package io.github.daddymean.agentickeyboard.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.learned.KeyboardLearnedData
import io.github.daddymean.agentickeyboard.learned.KeyboardStore
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * KEYBOARD-048: the keyboard database migration, with real files, real SQLite and real Room.
 * Only the cipher is a stand-in ([FakeCipherEngine]); SqlCipherEngine is covered by
 * KeyboardDbSqlCipherTest on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardDbEncryptionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "kbcrypt_test_db"
    private val main: File get() = context.getDatabasePath(name)
    private val keyFile: File get() = File(context.noBackupFilesDir, "kbcrypt_test/db.key")
    private val engine = FakeCipherEngine()
    private val wrapper = FakeKeyWrapper()
    private var clock = 42L
    private val health: KeyboardDbHealth get() = KeyboardDbHealth(File(keyFile.parentFile, "health"), { clock }, backoffMs = 0)
    private var status: KeyboardDbStatus? = null
    private val open = mutableListOf<AppDatabase>()

    @Before
    fun clean() {
        KeyboardDbHealth.newLaunch()
        main.parentFile?.listFiles()?.filter { it.name.startsWith(name) }?.forEach { it.delete() }
        keyFile.parentFile?.listFiles()?.forEach { it.delete() }
        keyFile.delete()
        context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun close() = open.forEach { it.close() }

    private fun migrator() = KeyboardDbMigrator(KeyboardDbKey(keyFile, wrapper), engine, { clock }, health)

    private fun encrypted(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addMigrations(AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
        .addCallback(AppDatabase.SECURE_DELETE)
        .openHelperFactory(KeyboardDbOpenHelperFactory({ migrator() }, engine) { status = it })
        .allowMainThreadQueries().build().also(open::add)
        .also { it.openHelper.writableDatabase } // Room opens lazily; open now so the migration runs

    /** The database as v8 builds before this PR left it: plaintext, WAL, framework helper. */
    private fun plaintext(): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addCallback(AppDatabase.SECURE_DELETE).allowMainThreadQueries().build().also(open::add)

    private fun AppDatabase.done() { close(); open.remove(this) }

    private fun seedPlaintext() = runBlocking {
        val db = plaintext()
        val repo = KeyboardRepository(db)
        repo.insertWords(listOf(UserVocabulary("hello", 5, 1_000), UserVocabulary("keith", 9, 3_000)))
        repo.recordBigram("hello", "keith")
        repo.insertCorrections(listOf(LearnedCorrection(typo = "teh", correction = "the", count = 4)))
        repo.insertLogs(listOf(WritingLog(originalText = "secret plan", sentiment = "Calm", toneScore = .5f, wordCount = 2, timestamp = 10)))
        repo.upsertAppPersonas(listOf(AppPersona("com.slack", "Professional", "Slack")))
        repo.captureClipboard("clip a", "h1", seenAt = System.currentTimeMillis())
        db.done()
        assertEquals(KeyboardDbMigrator.Kind.PLAINTEXT, migrator().kind(main))
    }

    private fun assertSeedIntact(db: AppDatabase) = runBlocking {
        val repo = KeyboardRepository(db)
        assertEquals(9, repo.getWord("keith")?.count)
        assertEquals(listOf("keith"), repo.nextWords("hello"))
        assertEquals(1, db.learnedCorrectionDao().count())
        assertEquals(1, db.clipboardHistoryDao().count())
        assertEquals(1, db.query("SELECT count(*) FROM writing_logs", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(1, db.query("SELECT count(*) FROM app_personas", null).use { it.moveToFirst(); it.getInt(0) })
    }

    private fun leftovers() = main.parentFile!!.listFiles()!!.map { it.name }.filter { it.startsWith("$name.kbcrypt") }

    @Test
    fun freshInstallCreatesAnEncryptedDatabase() = runBlocking {
        val db = encrypted()
        KeyboardRepository(db).insertWords(listOf(UserVocabulary("hi", 1, 1)))
        assertEquals("encrypted", status?.state)
        assertFalse(status!!.migrated)
        assertEquals(0, engine.exports)
        assertTrue(keyFile.isFile)
        db.done()
        assertEquals(KeyboardDbMigrator.Kind.ENCRYPTED, migrator().kind(main))
        // Reopening uses the same key and finds the word.
        assertEquals(1, encrypted().userVocabularyDao().count())
        assertEquals("encrypted", status?.state)
    }

    @Test
    fun existingPlaintextDatabaseIsMigratedInPlaceWithEveryRow() {
        seedPlaintext()
        val db = encrypted()
        assertSeedIntact(db)
        assertEquals("encrypted", status?.state)
        assertTrue(status!!.migrated)
        db.done()
        assertEquals(KeyboardDbMigrator.Kind.ENCRYPTED, migrator().kind(main))
        assertEquals(emptyList<String>(), leftovers())
        assertFalse(File(main.path + "-journal").length() > 0)
    }

    @Test
    fun migrationIsIdempotent() {
        seedPlaintext()
        encrypted().done()
        val db = encrypted()
        assertSeedIntact(db)
        assertFalse(status!!.migrated)
        assertEquals(1, engine.exports)
    }

    @Test
    fun failedCopyKeepsThePlaintextWorkingAndRetriesNextLaunch() {
        seedPlaintext()
        engine.exportFails = true
        val first = encrypted()
        assertSeedIntact(first)
        assertEquals("plaintext:copy", status?.state)
        // Typing still works on the plaintext database this launch.
        runBlocking { KeyboardRepository(first).insertWords(listOf(UserVocabulary("later", 1, 9))) }
        first.done()
        assertEquals(KeyboardDbMigrator.Kind.PLAINTEXT, migrator().kind(main))
        assertEquals(emptyList<String>(), leftovers())

        engine.exportFails = false
        val second = encrypted()
        assertSeedIntact(second)
        assertEquals(1, runBlocking { KeyboardRepository(second).getWord("later")?.count })
        assertTrue(status!!.migrated)
    }

    @Test
    fun copyThatDoesNotMatchIsThrownAwayNotSwapped() {
        seedPlaintext()
        engine.corruptCopy = true
        val db = encrypted()
        assertEquals("plaintext:copy", status?.state)
        assertSeedIntact(db)
        db.done()
        assertEquals(KeyboardDbMigrator.Kind.PLAINTEXT, migrator().kind(main))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun crashDuringCopyLeavesAPartialCopyThatIsDiscarded() {
        seedPlaintext()
        File(main.path + ".kbcrypt-tmp").writeBytes(ByteArray(8192) { 3 })
        File(main.path + ".kbcrypt-tmp-journal").writeBytes(ByteArray(512) { 3 })
        val db = encrypted()
        assertSeedIntact(db)
        assertTrue(status!!.migrated)
        db.done()
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun crashBetweenTheTwoRenamesRestoresThePlaintextThenMigrates() {
        seedPlaintext()
        // State after "plaintext → .kbcrypt-plain" with the verified copy not yet renamed in.
        val key = KeyboardDbKey(keyFile, wrapper).create()
        val tmp = File(main.path + ".kbcrypt-tmp")
        engine.exportEncrypted(main, tmp, key)
        assertTrue(main.renameTo(File(main.path + ".kbcrypt-plain")))
        assertFalse(main.exists())

        val db = encrypted()
        assertSeedIntact(db)
        assertTrue(status!!.migrated)
        db.done()
        assertEquals(KeyboardDbMigrator.Kind.ENCRYPTED, migrator().kind(main))
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test
    fun crashBeforeTheShredFinishesTheShredNextLaunch() {
        seedPlaintext()
        val plainBytes = main.readBytes()
        encrypted().done() // migrated
        val old = File(main.path + ".kbcrypt-plain")
        old.writeBytes(plainBytes) // as if the shred never ran
        val db = encrypted()
        assertSeedIntact(db)
        assertFalse(old.exists())
        assertEquals("encrypted", status?.state)
    }

    @Test
    fun shredOverwritesBeforeDeleting() {
        val f = File(context.cacheDir, "shred-me").apply { writeBytes("secret plan".toByteArray()) }
        val handle = java.io.RandomAccessFile(f, "r") // keeps the inode readable after unlink
        KeyboardDbMigrator.shred(f)
        assertFalse(f.exists())
        val left = ByteArray(11).also { handle.seek(0); handle.readFully(it) }
        handle.close()
        assertArrayEquals(ByteArray(11), left)
    }

    @Test
    fun withoutTheEngineAPlaintextOrNewDatabaseStaysPlaintext() = runBlocking {
        engine.loadFails = true
        val db = encrypted()
        KeyboardRepository(db).insertWords(listOf(UserVocabulary("hi", 1, 1)))
        assertEquals("plaintext:engine", status?.state)
        db.done()
        assertEquals(KeyboardDbMigrator.Kind.PLAINTEXT, migrator().kind(main))
        engine.loadFails = false
        assertEquals(1, encrypted().userVocabularyDao().count())
        assertTrue(status!!.migrated)
    }

    @Test
    fun anEncryptedFileIsNeverOpenedOrTouchedWhenTheKeyIsOnlyUnavailable() = runBlocking {
        seedPlaintext()
        encrypted().done()
        val bytes = main.readBytes()
        wrapper.failUnwraps = 99
        val db = encrypted()
        assertEquals("memory:key", status?.state)
        assertEquals(0, db.userVocabularyDao().count())
        KeyboardRepository(db).insertWords(listOf(UserVocabulary("tmp", 1, 1)))
        db.done()
        assertArrayEquals(bytes, main.readBytes())
        wrapper.failUnwraps = 0
        assertSeedIntact(encrypted())
    }

    @Test
    fun transientUnwrapFailureIsRetried() {
        seedPlaintext()
        encrypted().done()
        wrapper.failUnwraps = 2
        assertSeedIntact(encrypted())
        assertEquals("encrypted", status?.state)
    }

    @Test
    fun anEncryptedFileWithoutItsEngineRunsInMemory() {
        seedPlaintext()
        encrypted().done()
        engine.loadFails = true
        encrypted().done()
        assertEquals("memory:engine", status?.state)
        assertEquals(KeyboardDbMigrator.Kind.ENCRYPTED, migrator().kind(main))
    }

    @Test
    fun aLostKeySetsTheUnreadableFileAsideAndStartsFresh() = runBlocking {
        seedPlaintext()
        encrypted().done()
        val bytes = main.readBytes()
        wrapper.present = false
        val db = encrypted()
        assertEquals("encrypted", status?.state)
        assertEquals(0, db.userVocabularyDao().count())
        val aside = File(main.path + ".kbcrypt-unreadable-42")
        assertArrayEquals(bytes, aside.readBytes())
        assertTrue(File(keyFile.path + ".lost-42").isFile)
        assertTrue(keyFile.isFile) // a new wrapped key, not an overwrite of the old one
        assertFalse(File(keyFile.path + ".lost-42").readBytes().contentEquals(keyFile.readBytes()))
    }

    @Test
    fun deleteScreenStillWorksOnTheEncryptedDatabase() = runBlocking {
        seedPlaintext()
        val db = encrypted()
        assertTrue(status!!.migrated)
        assertTrue(AppDatabase.enableSecureDelete(db.openHelper.writableDatabase))
        val data = KeyboardLearnedData(db, KeyboardSettings(context), context.getSharedPreferences("kbcrypt_cleanup", Context.MODE_PRIVATE))
        val counts = data.counts()
        assertEquals(2, counts[KeyboardStore.WORDS])
        assertEquals(1, counts[KeyboardStore.CLIPBOARD])
        val hello = data.entries(KeyboardStore.WORDS, 10).first { it.text == "hello" }
        assertEquals(2, data.delete(hello)) // the word and its next-word pair
        assertNull(KeyboardRepository(db).getWord("hello"))
        assertEquals(1, data.clear(KeyboardStore.CLIPBOARD))
        assertEquals(1, data.clear(KeyboardStore.WRITING_LOG))
        assertTrue(data.scrubWal())
        assertEquals(0L, File(main.path + "-wal").length())
        assertNotNull(KeyboardRepository(db).getWord("keith"))
    }

    @Test
    fun keyRoundTripsAndReportsLossCorrectly() {
        val key = KeyboardDbKey(keyFile, wrapper)
        assertTrue(key.read() is KeyboardDbKey.Read.Lost)
        val made = key.create()
        assertTrue(String(made, Charsets.US_ASCII).matches(Regex("x'[0-9a-f]{64}'")))
        assertArrayEquals(made, (key.read() as KeyboardDbKey.Read.Ok).sqlKey)
        assertFalse(File(keyFile.path + ".tmp").exists())
        // The raw key never sits in the file unwrapped.
        val raw = String(made, Charsets.US_ASCII).substring(2, 66).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertFalse(keyFile.readBytes().toList().windowed(raw.size).any { it == raw.toList() })
        wrapper.failUnwraps = 99
        assertTrue(key.read() is KeyboardDbKey.Read.Unavailable)
        wrapper.present = false
        assertTrue(key.read() is KeyboardDbKey.Read.Lost)
        wrapper.present = true; wrapper.failUnwraps = 0
        keyFile.writeBytes(byteArrayOf(9, 9, 9))
        assertTrue(key.read() is KeyboardDbKey.Read.Lost) // a damaged key file can never open
    }

    @Test
    fun walOnlyDatabaseIsDetectedFromItsFirstFrame() {
        main.parentFile!!.mkdirs()
        main.writeBytes(ByteArray(0))
        val wal = File(main.path + "-wal")
        assertEquals(KeyboardDbMigrator.Kind.MISSING, migrator().kind(main))
        wal.writeBytes(ByteArray(56) + KeyboardDbMigrator.MAGIC + ByteArray(100))
        assertEquals(KeyboardDbMigrator.Kind.PLAINTEXT, migrator().kind(main))
        wal.writeBytes(ByteArray(56) + ByteArray(16) { 9 } + ByteArray(100))
        assertEquals(KeyboardDbMigrator.Kind.ENCRYPTED, migrator().kind(main))
    }

    @Test
    fun transientCanOpenLeavesTheFileAndDoesNotStartEmpty() = runBlocking {
        seedPlaintext()
        encrypted().done()
        val bytes = main.readBytes()
        engine.forcedProbe = OpenProbe.Transient
        val db = encrypted()
        assertEquals("memory:transient", status?.state)
        assertTrue(main.isFile)
        assertArrayEquals(bytes, main.readBytes())
        KeyboardRepository(db).insertWords(listOf(UserVocabulary("tmp", 1, 1)))
        assertEquals(0, db.userVocabularyDao().count()) // writes are blocked
        db.done()
        engine.forcedProbe = null
        assertSeedIntact(encrypted())
    }

    @Test
    fun recoverDoesNotShredPlaintextUntilTheNewFileVerifies() {
        seedPlaintext()
        val plainBytes = main.readBytes()
        val key = KeyboardDbKey(keyFile, wrapper).create()
        val tmp = File(main.path + ".kbcrypt-tmp")
        engine.exportEncrypted(main, tmp, key)
        // A fresh, empty "encrypted" main plus the real plaintext as the sidecar: the swap
        // never finished, and a later create wrote a new main. Counts will not match.
        tmp.delete()
        val empty = File(main.path + ".empty")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(empty, null).use { db ->
            db.execSQL("PRAGMA application_id = ${FakeCipherEngine.APP_ID}")
            db.execSQL("CREATE TABLE IF NOT EXISTS ${FakeCipherEngine.KEY_TABLE} (k BLOB)")
            db.execSQL("INSERT INTO ${FakeCipherEngine.KEY_TABLE} VALUES (?)", arrayOf<Any>(key))
        }
        assertTrue(main.renameTo(File(main.path + ".kbcrypt-plain")))
        assertTrue(empty.renameTo(main))
        val db = encrypted()
        assertSeedIntact(db)
        assertFalse(File(main.path + ".kbcrypt-plain").exists() && File(main.path + ".kbcrypt-plain").length() > 0)
        // The original rows survived (restored, then migrated).
        db.done()
        assertEquals(KeyboardDbMigrator.Kind.ENCRYPTED, migrator().kind(main))
    }

    @Test
    fun failedMigrationBacksOffInsteadOfRetryingImmediately() {
        seedPlaintext()
        val timed = KeyboardDbHealth(File(keyFile.parentFile, "health"), { clock }, backoffMs = 60_000)
        fun timedMigrator() = KeyboardDbMigrator(KeyboardDbKey(keyFile, wrapper), engine, { clock }, timed)
        engine.exportFails = true
        val first = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(AppDatabase.SECURE_DELETE)
            .openHelperFactory(KeyboardDbOpenHelperFactory({ timedMigrator() }, engine) { status = it })
            .allowMainThreadQueries().build().also(open::add)
            .also { it.openHelper.writableDatabase }
        assertEquals("plaintext:copy", status?.state)
        first.done()
        assertEquals(1, engine.exports)
        engine.exportFails = false
        // Still inside the backoff window: no second export.
        val second = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(AppDatabase.SECURE_DELETE)
            .openHelperFactory(KeyboardDbOpenHelperFactory({ timedMigrator() }, engine) { status = it })
            .allowMainThreadQueries().build().also(open::add)
            .also { it.openHelper.writableDatabase }
        assertEquals("plaintext:backoff", status?.state)
        assertEquals(1, engine.exports)
        assertSeedIntact(second)
        second.done()
        clock += 60_000
        val third = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addCallback(AppDatabase.SECURE_DELETE)
            .openHelperFactory(KeyboardDbOpenHelperFactory({ timedMigrator() }, engine) { status = it })
            .allowMainThreadQueries().build().also(open::add)
            .also { it.openHelper.writableDatabase }
        assertTrue(status!!.migrated)
        assertEquals("encrypted", status?.state)
        assertEquals(2, engine.exports)
        assertSeedIntact(third)
    }

    @Test
    fun permanentlyInvalidatedKeyIsLostAndTheOldKeyFileIsKeptAside() = runBlocking {
        seedPlaintext()
        encrypted().done()
        val oldKey = keyFile.readBytes()
        wrapper.throwPermanentlyInvalidated = true
        val db = encrypted()
        assertEquals("encrypted", status?.state)
        assertEquals(0, db.userVocabularyDao().count())
        assertTrue(File(main.path + ".kbcrypt-unreadable-42").isFile)
        assertArrayEquals(oldKey, File(keyFile.path + ".lost-42").readBytes())
        assertTrue(keyFile.isFile)
        assertFalse(keyFile.readBytes().contentEquals(oldKey))
    }

    @Test
    fun aGenericInvalidKeyExceptionIsUnavailableAndDoesNotSetTheDatabaseAside() = runBlocking {
        seedPlaintext()
        encrypted().done()
        val bytes = main.readBytes()
        val oldKey = keyFile.readBytes()
        wrapper.throwInvalidKey = true
        val db = encrypted()
        assertEquals("memory:key", status?.state)
        assertFalse(File(main.path + ".kbcrypt-unreadable-42").exists())
        assertArrayEquals(bytes, main.readBytes())
        assertArrayEquals(oldKey, keyFile.readBytes())
        db.done()
    }

    @Test
    fun threeUnavailableOpensOfferAResetThatStartsFreshWithoutDeleting() = runBlocking {
        seedPlaintext()
        encrypted().done()
        val bytes = main.readBytes()
        val oldKey = keyFile.readBytes()
        wrapper.failUnwraps = 99
        repeat(3) {
            KeyboardDbHealth.newLaunch()
            encrypted().done()
        }
        assertTrue(status!!.offerReset)
        assertEquals(3, status!!.unavailableCount)
        assertEquals("The keyboard couldn't open its encrypted data this time. Nothing new is being saved to the phone, and deleting here wouldn't change what's stored.", KeyboardDbUserStatus.banner(status))
        assertEquals("The keyboard's saved data isn't available right now, so nothing was deleted on the phone.", KeyboardDbUserStatus.refuseDelete(status))
        assertEquals("Start a new encrypted database", KeyboardDbUserStatus.resetLabel(status))
        wrapper.failUnwraps = 0
        wrapper.present = true
        // Reset moves the file and key aside and starts empty.
        val outcome = migrator().resetBroken(main)
        assertTrue(outcome is KeyboardDbMigrator.Outcome.Encrypted)
        assertArrayEquals(bytes, File(main.path + ".kbcrypt-unreadable-42").readBytes())
        assertArrayEquals(oldKey, File(keyFile.path + ".lost-42").readBytes())
        assertFalse(main.exists())
    }

    @Test
    fun getDatabaseRetriesAnEphemeralInstanceButNotAStableOne() {
        assertTrue(AppDatabase.shouldRecycle(KeyboardDbStatus("memory:key", false, 0, unavailableCount = 1)))
        assertFalse(AppDatabase.shouldRecycle(KeyboardDbStatus("encrypted", false, 0)))
        assertFalse(AppDatabase.shouldRecycle(KeyboardDbStatus("plaintext:copy", false, 0)))
        assertFalse(AppDatabase.shouldRecycle(null))
    }

    @Test
    fun classifyOpenFailureTreatsOnlyCorruptionAsUnopenable() {
        assertEquals(OpenProbe.Unopenable, classifyOpenFailure(android.database.sqlite.SQLiteDatabaseCorruptException("file is not a database")))
        assertEquals(OpenProbe.Unopenable, classifyOpenFailure(android.database.sqlite.SQLiteException("file is not a database (26)")))
        assertEquals(OpenProbe.Transient, classifyOpenFailure(android.database.sqlite.SQLiteException("database is locked")))
        assertEquals(OpenProbe.Transient, classifyOpenFailure(OutOfMemoryError("heap")))
    }

    @Test
    fun writeBlockingRejectsMutatingSqlAndAllowsReads() {
        assertTrue(isMutatingSql("INSERT INTO t VALUES (1)"))
        assertTrue(isMutatingSql("WITH x AS (SELECT 1) INSERT INTO t SELECT * FROM x"))
        assertFalse(isMutatingSql("SELECT * FROM t"))
        assertFalse(isMutatingSql("PRAGMA user_version"))
        assertFalse(isMutatingSql("BEGIN TRANSACTION"))
    }

    @Test
    fun frameworkCorruptionHandlerDoesNotDeleteTheFile() {
        val f = context.getDatabasePath("keep_file_test")
        f.parentFile!!.mkdirs()
        f.writeBytes("not a database".toByteArray())
        val cb = object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
            override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, o: Int, n: Int) = Unit
        }
        val helper = neverDeleteFramework().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name("keep_file_test").callback(cb).build()
        )
        runCatching { helper.writableDatabase }
        assertTrue(f.isFile)
        assertEquals("not a database", f.readText())
        helper.close()
    }

    @Test
    fun unavailableIsCountedAtMostOncePerLaunch() = runBlocking {
        seedPlaintext()
        encrypted().done()
        wrapper.failUnwraps = 99
        repeat(5) { encrypted().done() }
        assertEquals(1, status!!.unavailableCount)
        assertFalse(status!!.offerReset)
        KeyboardDbHealth.newLaunch()
        encrypted().done()
        KeyboardDbHealth.newLaunch()
        encrypted().done()
        assertEquals(3, status!!.unavailableCount)
        assertTrue(status!!.offerReset)
    }

    @Test
    fun aCorruptMarkerMovesTheFileAsideOnTheNextPrepareBeforeOpening() {
        seedPlaintext()
        encrypted().done()
        val bytes = main.readBytes()
        migrator().flagCorruption(main)
        assertTrue(File(main.path + ".kbcrypt-corrupt").isFile)
        assertTrue(main.isFile)
        val next = encrypted()
        next.done()
        assertFalse(File(main.path + ".kbcrypt-corrupt").exists())
        val aside = File(main.path + ".kbcrypt-unreadable-42")
        assertArrayEquals(bytes, aside.readBytes())
        assertTrue(File(keyFile.path + ".lost-42").isFile)
        assertTrue(keyFile.isFile)
        // A new empty database was opened after the aside.
        assertTrue(main.isFile || status?.state == "encrypted")
    }

    @Test
    fun resetBrokenPairsDatabaseAndKeyUnderOneStamp() = runBlocking {
        seedPlaintext()
        encrypted().done()
        val bytes = main.readBytes()
        val oldKey = keyFile.readBytes()
        val outcome = migrator().resetBroken(main)
        assertTrue(outcome is KeyboardDbMigrator.Outcome.Encrypted)
        assertArrayEquals(bytes, File(main.path + ".kbcrypt-unreadable-42").readBytes())
        assertArrayEquals(oldKey, File(keyFile.path + ".lost-42").readBytes())
        assertFalse(main.exists())
    }

    @Test
    fun capNeverShredsAReadableAsideAndPairsKeyWithDatabase() {
        // Five plaintext unconfirmed asides stay; they are still readable (L4).
        val dbDir = main.parentFile!!
        dbDir.mkdirs()
        keyFile.parentFile!!.mkdirs()
        (1..5).forEach { n ->
            File(dbDir, "$name.kbcrypt-unconfirmed-$n").writeBytes("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII) + ByteArray(16))
        }
        migrator().capPairedAsides(main, cap = 3)
        val unconfirmed = dbDir.listFiles()!!.filter { it.name.contains("unconfirmed") }.map { it.name }.sorted()
        assertEquals(5, unconfirmed.size)

        // Unreadable encrypted-looking asides with no key are not readable and are capped.
        dbDir.listFiles()!!.filter { it.name.contains("unconfirmed") }.forEach { it.delete() }
        (1..5).forEach { n ->
            // Random bytes: not a plaintext SQLite header, no matching key.
            File(dbDir, "$name.kbcrypt-unreadable-$n").writeBytes(ByteArray(64) { n.toByte() })
            File(keyFile.path + ".lost-$n").writeBytes(ByteArray(8) { n.toByte() })
        }
        // Damaged key asides won't unwrap, so the pairs are not readable. Cap keeps newest 3 pairs.
        migrator().capPairedAsides(main, cap = 3)
        val leftDb = dbDir.listFiles()!!.filter { it.name.contains("unreadable") }.map { it.name.substringAfter("unreadable-").toInt() }.sorted()
        val leftKey = keyFile.parentFile!!.listFiles()!!.filter { it.name.contains(".lost-") }.map { it.name.substringAfter(".lost-").toInt() }.sorted()
        assertEquals(listOf(3, 4, 5), leftDb)
        assertEquals(listOf(3, 4, 5), leftKey)
    }

    @Test
    fun healthLoadAndSaveFailuresDoNotThrow() {
        val target = File(keyFile.parentFile, "health")
        target.parentFile!!.mkdirs()
        // A directory where a file is expected: load must not throw.
        val asDir = File(keyFile.parentFile, "health-dir")
        asDir.mkdirs()
        File(asDir, "health").mkdirs()
        KeyboardDbHealth(File(asDir, "health")) // load of a directory
        // Save onto a path whose parent cannot be created as a directory (parent is a file).
        val blocker = File(keyFile.parentFile, "blocked")
        blocker.writeText("x")
        KeyboardDbHealth(File(blocker, "health/file")).recordMigrationFailure("copy")
    }


    @Test
    fun aKillDuringThePlaintextZeroPassKeepsTheEncryptedCopy() = runBlocking {
        seedPlaintext()
        val plainBytes = main.readBytes()
        encrypted().done()
        assertEquals(KeyboardDbMigrator.Kind.ENCRYPTED, migrator().kind(main))
        val good = main.readBytes()
        val old = File(main.path + ".kbcrypt-plain")
        if (plainBytes.size > 8192) {
            old.writeBytes(ByteArray(8192) { 0 } + plainBytes.copyOfRange(8192, plainBytes.size))
        } else {
            old.writeBytes(ByteArray(maxOf(plainBytes.size, 8192)) { 0 })
        }
        val db = encrypted()
        assertSeedIntact(db)
        assertEquals("encrypted", status?.state)
        assertFalse(old.exists())
        assertFalse(File(main.path + ".kbcrypt-shredding").exists())
        assertFalse(main.parentFile!!.listFiles()!!.any { it.name.contains("unconfirmed") })
        assertArrayEquals(good, main.readBytes())
        db.done()
    }

    @Test
    fun aRepositoryHeldAcrossARecycleUsesTheNewDatabaseAndTheOldHandleStaysOpen() = runBlocking {
        val first = plaintext()
        KeyboardRepository(first).insertWords(listOf(UserVocabulary("kept", 3, 1)))
        var current: AppDatabase = first
        val repo = KeyboardRepository { current }
        assertEquals(3, repo.getWord("kept")?.count)
        val secondName = name + "_recovered"
        context.getDatabasePath(secondName).parentFile?.listFiles()?.filter { it.name.startsWith(secondName) }?.forEach { it.delete() }
        val second = Room.databaseBuilder(context, AppDatabase::class.java, secondName)
            .addCallback(AppDatabase.SECURE_DELETE)
            .allowMainThreadQueries().build().also(open::add)
        current = second
        // In-flight handle: the previous instance was not closed.
        assertEquals(3, first.userVocabularyDao().getWord("kept")?.count)
        repo.insertWords(listOf(UserVocabulary("recovered", 1, 1)))
        assertEquals(1, repo.getWord("recovered")?.count)
        assertNull(repo.getWord("kept"))
        assertEquals(3, first.userVocabularyDao().getWord("kept")?.count)
        second.done()
    }

    @Test
    fun withoutKeyStripsTheRawKeyFromAFailureMessage() {
        val sqlKey = KeyboardDbKey.sqlKey(ByteArray(32) { 0xaa.toByte() })
        val needle = String(sqlKey, Charsets.US_ASCII)
        val hex = sqlKey.decodeToString(2, 66)
        val inner = android.database.sqlite.SQLiteException("ATTACH failed for $needle key=$hex")
        val outer = IllegalStateException("export: $needle", inner)
        val cleaned = withoutKey(outer, sqlKey)
        val text = cleaned.stackTraceToString() + cleaned.message + (cleaned.cause?.message ?: "")
        assertFalse(text.contains(needle))
        assertFalse(text.contains(hex))
        assertTrue(cleaned.message!!.contains("<redacted>"))
    }
}
