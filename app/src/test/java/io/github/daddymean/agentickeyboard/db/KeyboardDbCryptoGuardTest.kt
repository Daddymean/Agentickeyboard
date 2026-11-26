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
        assertEquals(4, opens.size)
        assertTrue(opens.all { it.contains("KeepFile") })
        assertTrue(engine.contains("class KeepFile"))
        assertTrue(engine.contains("SqlCipherOpenHelperFactory"))
        assertFalse(engine.contains("SupportOpenHelperFactory"))
        val crypto = src("db/KeyboardDbCrypto.kt")
        assertTrue(crypto.contains("flagCorruption"))
        assertTrue(crypto.contains("consumeCorruptMarker"))
        assertTrue(crypto.contains("migrator.flagCorruption(file)"))
        assertFalse("corruption handler must not rename under open connections",
            crypto.contains("migrator.setAside(file)"))
    }

    @Test
    fun keyboardDatabaseOpensThroughTheEncryptingFactory() {
        assertTrue(models.contains(".openHelperFactory(encryptedFactory(app, engineForTests ?: SqlCipherEngine(), wrapperForTests ?: KeystoreDbKeyWrapper()))"))
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

    @Test
    fun rawKeyNeverAppearsInSqlText() {
        val export = engine.substringAfter("fun exportEncrypted").substringBefore("override fun verify")
        assertFalse(export.contains("String(sqlKey"))
        assertFalse(export.contains("KEY \""))
        assertFalse(export.contains("KEY \""))
        assertTrue(export.contains("KEY ''"))
        assertTrue(export.contains("openOrCreateDatabase(dest, sqlKey"))
        assertTrue(export.contains("withoutKey"))
        assertTrue(engine.contains("fun withoutKey"))
        assertTrue(engine.contains("fun isRawKey"))
    }

    @Test
    fun versionCodeWasBumpedSoOldApksCannotDowngradeOntoTheEncryptedFile() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        val code = gradle.lineSequence()
            .map { it.trim() }
            .first { it.startsWith("versionCode") }
            .substringAfter("=").trim().toInt()
        assertTrue("versionCode must be > 1, was $code", code > 1)
    }

    @Test
    fun ephemeralInstancesAreNotCachedAndTheDeleteScreenRefuses() {
        val models = src("db/DatabaseModels.kt")
        assertTrue(models.contains("shouldRecycle(lastStatus)"))
        assertTrue(models.contains("fun resetBrokenDatabase"))
        val app = src("AgenticKeyboardApplication.kt")
        assertTrue(app.contains("val database get()"))
        assertFalse(app.contains("val database by lazy"))
        val activity = src("KeyboardLearnedActivity.kt")
        assertTrue(activity.contains("KeyboardDbUserStatus.banner"))
        assertTrue(activity.contains("refuseDelete"))
        assertTrue(activity.contains("confirmReset()"))
        assertTrue(activity.substringAfter("private fun confirmReset()").contains(".showSecure()"))
        assertTrue(activity.substringAfter("private fun confirm(").contains("refuseDelete"))
    }
}
