package io.github.daddymean.agentickeyboard.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * KEYBOARD-048 on a device or emulator: the real SQLCipher library and the real Keystore
 * (under a test-only alias). Not run by CI (no emulator there); run with
 * `./gradlew connectedDebugAndroidTest --tests '*KeyboardDbSqlCipherTest*'`.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardDbSqlCipherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "kbcrypt_device_test"
    private val main get() = context.getDatabasePath(name)
    private val keyFile get() = File(context.noBackupFilesDir, "kbcrypt_device_test/db.key")
    private val wrapper = KeystoreDbKeyWrapper("agentic_keyboard_db_key_test")
    private var status: KeyboardDbStatus? = null

    @Before
    @After
    fun clean() {
        main.parentFile?.listFiles()?.filter { it.name.startsWith(name) }?.forEach { it.delete() }
        keyFile.delete()
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("agentic_keyboard_db_key_test")
    }

    private fun build(encrypted: Boolean): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, name)
        .addCallback(AppDatabase.SECURE_DELETE)
        .apply {
            if (encrypted) openHelperFactory(
                KeyboardDbOpenHelperFactory({ KeyboardDbMigrator(KeyboardDbKey(keyFile, wrapper), SqlCipherEngine()) }, SqlCipherEngine()) { status = it }
            )
        }
        .build()

    @Test
    fun plaintextDatabaseMigratesToSqlCipherAndPlaintextIsGone() = runBlocking {
        val plain = build(encrypted = false)
        KeyboardRepository(plain).insertWords((1..500).map { UserVocabulary("word$it", it, it.toLong()) })
        KeyboardRepository(plain).captureClipboard("secret clip text", "h1", seenAt = System.currentTimeMillis())
        plain.close()
        assertTrue(String(main.readBytes()).contains("secret clip text"))

        val db = build(encrypted = true)
        assertEquals(500, db.userVocabularyDao().count())
        assertEquals("encrypted", status?.state)
        android.util.Log.i("KeyboardDbSqlCipherTest", "migrate prepare ms=${status!!.prepareMillis}")
        assertTrue(status!!.migrated)
        assertTrue(AppDatabase.enableSecureDelete(db.openHelper.writableDatabase))
        db.close()

        val bytes = main.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).startsWith("SQLite format 3"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("secret clip text"))
        assertFalse(File(main.path + ".kbcrypt-plain").exists())
        assertFalse(File(main.path + ".kbcrypt-tmp").exists())

        val again = build(encrypted = true)
        assertEquals(500, again.userVocabularyDao().count())
        assertFalse(status!!.migrated)
        android.util.Log.i("KeyboardDbSqlCipherTest", "reopen prepare ms=${status!!.prepareMillis}")
        again.close()
    }

    @Test
    fun wrongKeyNeverDeletesTheFile() {
        build(encrypted = true).apply { runBlocking { KeyboardRepository(this@apply).insertWords(listOf(UserVocabulary("a", 1, 1))) }; close() }
        val bytes = main.readBytes()
        assertEquals(OpenProbe.Unopenable, SqlCipherEngine().canOpen(main, KeyboardDbKey.sqlKey(ByteArray(32))))
        assertTrue(bytes.contentEquals(main.readBytes()))
    }

    @Test
    fun aDamagedPageDoesNotDeleteTheDatabase() = runBlocking {
        val db = build(encrypted = true)
        KeyboardRepository(db).insertWords((1..800).map { UserVocabulary("word$it", it, it.toLong()) })
        db.close()
        val original = main.readBytes()
        require(original.size > 8192)
        java.io.RandomAccessFile(main, "rw").use { f ->
            f.seek(6000)
            f.write(byteArrayOf(0x5A, 0x5A, 0x5A, 0x5A, 0x5A, 0x5A, 0x5A, 0x5A))
        }
        // First open after the damage: the live file must stay at its path (N1). A mid-file
        // page leaves the header readable, so prepare does not move it; the handler only flags.
        val again = build(encrypted = true)
        runCatching { again.userVocabularyDao().count() }
        again.close()
        assertTrue("live database must stay at its path while connections may still be open", main.isFile)
        assertEquals(original.size.toLong(), main.length())
        assertEquals(0x5A.toByte(), main.readBytes()[6000])
        val movedOnFirstOpen = main.parentFile!!.listFiles()!!.any { it.name.contains("kbcrypt-unreadable") }
        assertFalse("must not move the live file aside under open connections", movedOnFirstOpen)
        assertTrue(File(main.path + ".kbcrypt-corrupt").isFile)

        // Next launch, before any connection opens, the flagged file is moved aside and kept.
        val third = build(encrypted = true)
        runCatching { third.userVocabularyDao().count() }
        third.close()
        assertFalse(File(main.path + ".kbcrypt-corrupt").exists())
        val aside = main.parentFile!!.listFiles()!!.single { it.name.startsWith("$name.kbcrypt-unreadable-") && !it.name.contains("-wal") && !it.name.contains("-shm") && !it.name.contains("-journal") }
        assertEquals(0x5A.toByte(), aside.readBytes()[6000])
        assertEquals(original.size.toLong(), aside.length())
        if (main.isFile) {
            val live = main.readBytes()
            assertFalse(live.size > 6008 && live[6000] == 0x5A.toByte() && main.length() == original.size.toLong())
        }
    }

    @Test
    fun aFailedExportMessageDoesNotContainTheKey() {
        val plain = build(encrypted = false)
        runBlocking { KeyboardRepository(plain).insertWords(listOf(UserVocabulary("a", 1, 1))) }
        plain.close()
        val sqlKey = KeyboardDbKey.sqlKey(ByteArray(32) { 0xab.toByte() })
        val needle = String(sqlKey, Charsets.US_ASCII)
        val blocked = File(context.cacheDir, "kbcrypt-not-a-dir").apply { writeText("x") }
        val dest = File(blocked, "copy.db")
        val error = runCatching { SqlCipherEngine().exportEncrypted(main, dest, sqlKey) }.exceptionOrNull()
        assertTrue(error != null)
        val text = (error!!.message ?: "") + (error.cause?.message ?: "") + error.stackTraceToString()
        assertFalse(text.contains(needle))
        assertFalse(text.contains("ab".repeat(32)))
        // And the scrubber itself never lets the key through.
        val cleaned = withoutKey(IllegalStateException("failed $needle"), sqlKey)
        assertFalse(cleaned.message!!.contains(needle))
        assertTrue(cleaned.message!!.contains("<redacted>"))
    }
}
