package io.github.daddymean.agentickeyboard.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** KEYBOARD-048 source guards for the parts Robolectric can't run (SQLCipher's native library, the Keystore). */
class KeyboardDbCryptoGuardTest {
    private val root = listOf(File("."), File("..")).first { File(it, "gradle/libs.versions.toml").exists() }
    private val base = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private fun src(path: String) = File(base, "java/io/github/daddymean/agentickeyboard/$path").readText()
    private val engine = src("db/SqlCipherEngine.kt")
    private val models = src("db/DatabaseModels.kt")
    private val key = src("db/KeyboardDbKey.kt")

    @Test
    fun everySqlCipherOpenKeepsTheFileOnError() {
        val opens = Regex("SQLiteDatabase\\.openDatabase\\(([^\\n]*)\\)").findAll(engine).map { it.groupValues[1] }.toList()
        assertEquals(3, opens.size)
        assertTrue(opens.all { it.contains("KeepFile") })
        assertTrue(Regex("object KeepFile : DatabaseErrorHandler \\{\\s*override fun onCorruption\\([^)]*\\) = Unit").containsMatchIn(engine))
    }

    @Test
    fun keyboardDatabaseOpensThroughTheEncryptingFactory() {
        assertTrue(models.contains(".openHelperFactory(encryptedFactory(context.applicationContext))"))
        assertTrue(models.contains(".addCallback(SECURE_DELETE)"))
        assertTrue(models.contains("engine: KeyboardDbEngine = SqlCipherEngine()"))
        assertTrue(models.contains("wrapper: DbKeyWrapper = KeystoreDbKeyWrapper()"))
    }

    @Test
    fun keyReusesTheTwinKeystoreCipherUnderItsOwnAliasAndNeverLeavesTheDevice() {
        assertTrue(key.contains("KeystoreTwinCipher(alias)"))
        assertTrue(key.contains("\"agentic_keyboard_db_key_v1\""))
        assertFalse(key.contains(io.github.daddymean.agentickeyboard.twin.KeystoreTwinCipher.KEY_ALIAS))
        assertTrue(key.contains("File(noBackupDir, \"keyboard_db/"))
        for (s in listOf(key, engine, src("db/KeyboardDbCrypto.kt"))) {
            for (banned in listOf("android.util.Log", "okhttp", "Retrofit", "http://", "https://", "printStackTrace")) {
                assertFalse("$banned in keyboard DB crypto", s.contains(banned))
            }
        }
    }

    @Test
    fun sqlCipherIsBsdLicensedAndCredited() {
        val notice = File(root, "third_party/sqlcipher/NOTICE").readText()
        assertTrue(notice.contains("BSD") && notice.contains("ZETETIC LLC"))
        assertTrue(File(base, "assets/third_party/SQLCipher-LICENSE.txt").readText().contains("ZETETIC LLC"))
        val versions = File(root, "gradle/libs.versions.toml").readText()
        assertTrue(versions.contains("name = \"sqlcipher-android\""))
        // Only the maintained sqlcipher-android artifact, not the deprecated android-database-sqlcipher.
        assertFalse(versions.contains("android-database-sqlcipher"))
    }
}
