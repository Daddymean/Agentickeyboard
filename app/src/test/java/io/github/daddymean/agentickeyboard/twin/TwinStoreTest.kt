package io.github.daddymean.agentickeyboard.twin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Robolectric has no Android Keystore, so these tests use the same AES-256-GCM
 * construction with a software key; [KeystoreTwinCipher] differs only in where
 * the key lives.
 */
class SoftwareTwinCipher : TwinCipher {
    var key: SecretKey? = newKey()
    var destroyed = 0

    override fun seal(plaintext: ByteArray, aad: ByteArray): TwinCipher.Sealed {
        val k = key ?: newKey().also { key = it }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return TwinCipher.Sealed(iv, c.doFinal(plaintext))
    }

    override fun open(sealed: TwinCipher.Sealed, aad: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key ?: error("gone"), GCMParameterSpec(128, sealed.iv))
        c.updateAAD(aad)
        return c.doFinal(sealed.ciphertext)
    }

    override fun destroyKey() { key = null; destroyed++ }

    private fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwinStoreTest {
    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var cipher: SoftwareTwinCipher
    private lateinit var store: TwinStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.noBackupFilesDir, "twin-test/twin_store.db")
        file.parentFile?.deleteRecursively()
        cipher = SoftwareTwinCipher()
        store = TwinStore(context, file, cipher)
    }

    @After
    fun tearDown() {
        store.close()
        file.parentFile?.deleteRecursively()
    }

    @Test
    fun storedTextAndPackageAreNotPlaintextOnDisk() {
        val secretPhrase = "ZebraMarmalade tell your sister I am proud of her"
        repeat(20) { store.add("$secretPhrase #$it", "com.example.chatapp", 1_000L + it) }
        assertEquals(20, store.count())
        store.close()

        val files = file.parentFile!!.listFiles()!!.filter { it.isFile }
        assertTrue(files.isNotEmpty())
        val needles = listOf("ZebraMarmalade", "proud of her", "com.example.chatapp")
        for (f in files) {
            val bytes = f.readBytes()
            val asUtf8 = String(bytes, Charsets.UTF_8)
            val asUtf16 = String(bytes, Charsets.UTF_16LE)
            for (needle in needles) {
                assertFalse("${f.name} contains $needle", asUtf8.contains(needle) || asUtf16.contains(needle))
            }
        }

        // ...and it still opens with the key.
        val reopened = TwinStore(context, file, cipher)
        val newest = reopened.recent(1).single()
        assertEquals("$secretPhrase #19", newest.text)
        assertEquals("com.example.chatapp", newest.packageName)
        reopened.close()
    }

    @Test
    fun deleteAllRemovesRowsFilesAndKey() {
        repeat(5) { store.add("message $it", "pkg", it.toLong()) }
        store.deleteAll()
        assertEquals(1, cipher.destroyed)
        assertFalse(file.exists())
        assertTrue(file.parentFile!!.listFiles()!!.none { it.name.startsWith(file.name) })
        assertEquals(0, store.count())
        assertTrue(store.recent(10).isEmpty())
        // Learning can continue afterwards with a fresh key.
        store.add("new start", "pkg", 99)
        assertEquals(listOf("new start"), store.recent(10).map { it.text })
    }

    @Test
    fun withoutTheKeyNothingOpens() {
        store.add("only readable with the key", "pkg", 5)
        cipher.destroyKey()
        cipher.key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertTrue(store.recent(10).isEmpty())
    }

    @Test
    fun singleEntryDelete() {
        val keep = store.add("keep me", "pkg", 1)
        val drop = store.add("drop me", "pkg", 2)
        assertTrue(store.delete(drop))
        assertEquals(listOf(keep), store.recent(10).map { it.id })
    }

    @Test
    fun reDatingARowBreaksItsSeal() {
        store.add("dated", "pkg", 10)
        store.close()
        android.database.sqlite.SQLiteDatabase.openDatabase(file.path, null, 0).use {
            it.execSQL("UPDATE twin_entries SET created_at = 11")
        }
        assertTrue(TwinStore(context, file, cipher).recent(10).isEmpty())
    }

    @Test
    fun nothingIsEverEvicted() {
        // Legacy purpose: the store never deletes on its own, however much it holds.
        val n = 5_000
        (1..n).forEach { store.add("history entry $it " + "x".repeat(200), "pkg", it.toLong()) }
        assertEquals(n, store.count())
        val all = ArrayList<Long>()
        store.forEach { all += it.atMillis }
        assertEquals((1..n).map { it.toLong() }, all)
        assertTrue(store.fileBytes() > 1_000_000)
        assertEquals("history entry 1 " + "x".repeat(200), store.recent(1, offset = n - 1).single().text)
    }

    @Test
    fun forEachVisitsEveryEntryInOrder() {
        (1..1_203).forEach { store.add("e$it", null, it.toLong()) }
        val seen = ArrayList<String>()
        store.forEach(pageSize = 500) { seen += it.text }
        assertEquals((1..1_203).map { "e$it" }, seen)
    }

    @Test
    fun productionStoreLivesInTheNoBackupDirectory() {
        val prod = TwinStore.storeFile(context)
        assertTrue(prod.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath))
    }

    @Test
    fun capturedSecretsNeverReachTheStore() {
        val session = TwinCaptureSession().apply {
            start("pkg", "", TwinCaptureSession.Gate(true, false, false, false))
        }
        var text = ""
        for (ch in "my password: hunter2") { text += ch; session.onText(text, 1L) }
        val candidate = session.onSend(text, 2L)!!
        TwinTextFilter.prepare(candidate.text)?.let { store.add(it, candidate.packageName, candidate.atMillis) }
        assertEquals(0, store.count())
    }

    @Test
    fun timingOnTheJvm() {
        // Main-thread cost per keystroke (length arithmetic) and the background cost
        // per stored message (filter + AES-GCM + SQLite insert). JVM/Robolectric only;
        // printed for the PR, with loose bounds so CI noise does not fail it.
        val s = TwinCaptureSession().apply { start("pkg", "", TwinCaptureSession.Gate(true, false, false, false)) }
        val sb = StringBuilder()
        val keystrokes = 200_000
        val t0 = System.nanoTime()
        for (i in 0 until keystrokes) {
            if (sb.length > 900) sb.setLength(0)
            sb.append('a')
            s.onText(sb.toString(), i.toLong())
        }
        val perKeyNs = (System.nanoTime() - t0) / keystrokes
        val message = "Hey buddy, proud of you for today. Call me after practice and we'll get pizza 🍕"
        repeat(50) { TwinTextFilter.prepare(message)?.let { m -> store.add(m, "pkg", it.toLong()) } }
        val n = 500
        val t1 = System.nanoTime()
        repeat(n) { TwinTextFilter.prepare(message)?.let { m -> store.add(m, "pkg", 1_000L + it) } }
        val perMessageUs = (System.nanoTime() - t1) / n / 1_000
        val bytesPerEntry = store.storedBytes() / store.count()
        println("TWIN_TIMING perKeystrokeNs=$perKeyNs perStoredMessageUs=$perMessageUs ciphertextBytesPerEntry=$bytesPerEntry")
        assertTrue(perKeyNs < 200_000)
    }
}
