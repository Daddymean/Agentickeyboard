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
        assertFalse(SqlCipherEngine().canOpen(main, KeyboardDbKey.sqlKey(ByteArray(32))))
        assertTrue(bytes.contentEquals(main.readBytes()))
    }
}
