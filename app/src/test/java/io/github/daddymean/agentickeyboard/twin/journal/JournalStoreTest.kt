package io.github.daddymean.agentickeyboard.twin.journal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import io.github.daddymean.agentickeyboard.twin.TwinStore
import io.github.daddymean.agentickeyboard.twin.TwinVault
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

/** KEYBOARD-025 acceptance rows 1–3: permission, recordings at rest, delete and delete-all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalStoreTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var cipher: SoftwareTwinCipher
    private lateinit var store: JournalStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.noBackupFilesDir, "twin-journal-test")
        dir.deleteRecursively()
        cipher = SoftwareTwinCipher()
        store = JournalStore(context, dir, cipher)
    }

    @After
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private val secret = "ZebraMarmalade I never told anyone about the lake house"

    private fun recording(): Pair<String, ByteArray> {
        val pcm = IntArray(20_000) { (kotlin.math.sin(it / 20.0) * 9000).toInt() + Random(it).nextInt(-50, 50) }
        val (id, out) = store.newRecording()
        out.use { FlacEncoder(it, 48_000, 16).apply { write(pcm); finish() } }
        return id to store.readRecording(id)
    }

    private fun allFiles() = dir.walkTopDown().filter { it.isFile }.toList()

    private fun assertNoPlaintext(vararg needles: String) {
        for (f in allFiles()) {
            val bytes = f.readBytes()
            val a = String(bytes, Charsets.ISO_8859_1)
            val u = String(bytes, Charsets.UTF_16LE)
            for (n in needles) assertFalse("${f.name} contains $n", a.contains(n) || u.contains(n))
        }
    }

    @Test
    fun answersTranscriptsAndAudioAreEncryptedAtRest() {
        val (audioId, flac) = recording()
        assertEquals("fLaC", String(flac, 0, 4, Charsets.US_ASCII))
        val body = JSONObject().put("promptId", "LS-001").put("text", secret).put("transcript", "Transcript $secret")
            .put("child", "Ava").put("audio", JSONArray().put(JSONObject().put("file", audioId).put("ms", 400)))
        store.add(JournalKinds.ANSWER, body, 1_000)
        store.add(JournalKinds.SETTINGS, JournalSettings(listOf("Ava", "Ben"), "Sam").toJson(), 2_000)
        store.close()
        assertNoPlaintext("ZebraMarmalade", "lake house", "Transcript", "fLaC", "LS-001", "\"Ben\"", "Sam")
        // Reopened with the key, everything is there.
        val r = JournalStore(context, dir, cipher)
        assertEquals(secret, r.all().first().body.getString("text"))
        assertArrayEquals(flac, r.readRecording(audioId))
        assertEquals(listOf(audioId), JournalStore.audioIds(r.all().first().body))
        r.close()
    }

    @Test
    fun deletingAnEntryDeletesItsRecordings() {
        val (a, _) = recording()
        val (b, _) = recording()
        val (keep, _) = recording()
        val id = store.add(JournalKinds.ANSWER, JSONObject().put("text", "x").put("audio",
            JSONArray().put(JSONObject().put("file", a)).put(JSONObject().put("file", b))), 1)
        store.add(JournalKinds.VOICE_SCRIPT, JSONObject().put("audio", JSONArray().put(JSONObject().put("file", keep))), 2)
        assertTrue(store.delete(id))
        assertEquals(1, store.count())
        assertEquals(listOf("$keep${JournalStore.AUDIO_SUFFIX}"), File(dir, JournalStore.AUDIO_DIR).list()!!.toList())
    }

    @Test
    fun unsavedTakesAreListedNotDeletedAndStayEncrypted() {
        val (saved, _) = recording()
        store.add(JournalKinds.ANSWER, JSONObject().put("audio", JSONArray().put(JSONObject().put("file", saved))), 1)
        val (orphan, _) = recording()
        // A take whose writer was never closed (process killed) is still ciphertext only.
        val (cut, out) = store.newRecording()
        out.write("fLaC-plaintext-header".toByteArray())
        assertNoPlaintext("fLaC")
        assertEquals(setOf(orphan, cut), store.unsavedRecordings().toSet())
        assertTrue(runCatching { store.readRecording(cut) }.isFailure)
        store.deleteAudio(orphan)
        assertEquals(listOf(cut), store.unsavedRecordings())
        assertTrue(runCatching { store.readRecording("../../etc/passwd") }.isFailure)
    }

    @Test
    fun deleteAllRemovesRowsAudioAndTheKeyAndReopensFresh() {
        val twinFile = File(dir, "twin_store.db")
        val twin = TwinStore(context, twinFile, cipher)
        twin.add("a typed message", "pkg", 1)
        val (audioId, _) = recording()
        store.add(JournalKinds.ANSWER, JSONObject().put("text", secret).put("audio", JSONArray().put(JSONObject().put("file", audioId))), 1)
        val oldKey = cipher.key
        TwinVault.deleteAll(store, twin)
        assertEquals(1, cipher.destroyed)
        assertTrue(allFiles().map { it.name }.toString(), allFiles().isEmpty())
        assertEquals(0, store.count())
        assertTrue(store.all().isEmpty())
        // Reopening after delete-all works with a fresh key.
        store.add(JournalKinds.ANSWER, JSONObject().put("text", "new start"), 5)
        assertFalse(oldKey == cipher.key)
        assertEquals(listOf("new start"), store.all().map { it.body.getString("text") })
        twin.close()
    }

    @Test
    fun productionJournalLivesInNoBackupStorage() {
        val d = JournalStore.dir(context)
        assertTrue(d.path.startsWith(context.noBackupFilesDir.path))
        assertFalse(d.path.startsWith(context.filesDir.path + File.separator))
    }

    @Test
    fun withoutMicPermissionRecordingRefusesAndCreatesNoFile() {
        val recorder = JournalRecorder(context, store)
        assertFalse(recorder.hasPermission())
        assertTrue(runCatching { recorder.start() }.isFailure)
        assertFalse(recorder.isRecording)
        assertTrue(allFiles().isEmpty())
        // Text answers still save.
        store.add(JournalKinds.ANSWER, JSONObject().put("text", "typed instead"), 1)
        assertEquals(1, store.count())
    }
}
