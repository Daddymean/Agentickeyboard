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
    private var status: KeyboardDbStatus? = null
    private val open = mutableListOf<AppDatabase>()

    @Before
    fun clean() {
        main.parentFile?.listFiles()?.filter { it.name.startsWith(name) }?.forEach { it.delete() }
        keyFile.delete()
        context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun close() = open.forEach { it.close() }

    private fun migrator() = KeyboardDbMigrator(KeyboardDbKey(keyFile, wrapper), engine) { 42L }

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
}
