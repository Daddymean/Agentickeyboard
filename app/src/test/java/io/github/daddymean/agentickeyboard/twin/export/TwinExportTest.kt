package io.github.daddymean.agentickeyboard.twin.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.TwinExportActivity
import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import io.github.daddymean.agentickeyboard.twin.TwinStore
import io.github.daddymean.agentickeyboard.twin.journal.FlacEncoder
import io.github.daddymean.agentickeyboard.twin.journal.JournalKinds
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.random.Random

/** KEYBOARD-026 acceptance rows 1–6. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwinExportTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var cipher: SoftwareTwinCipher
    private lateinit var twin: TwinStore
    private lateinit var journal: JournalStore
    private val pass = "correct horse battery staple".toCharArray()
    private val fast = 100_000 // the minimum; production uses PassphraseArchive.ITERATIONS
    private lateinit var audioId: String
    private lateinit var flac: ByteArray

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.noBackupFilesDir, "twin-export-test").also { it.deleteRecursively() }
        cipher = SoftwareTwinCipher()
        twin = TwinStore(context, File(dir, "twin_store.db"), cipher)
        journal = JournalStore(context, dir, cipher)
        twin.add("ZebraMarmalade see you at the lake on Saturday", "com.example.chat", 1_000)
        twin.add("call me on 415 555 0199 tonight", "com.example.chat", 2_000)
        twin.add("my password is hunter2", "com.example.chat", 3_000)
        // A recording larger than one archive chunk, so chunk order matters.
        val r = Random(5)
        val pcm = IntArray(48_000 * 12) { r.nextInt(-30_000, 30_000) }
        val (id, out) = journal.newRecording()
        out.use { FlacEncoder(it, 48_000, 16).apply { write(pcm); finish() } }
        audioId = id
        flac = journal.readRecording(id)
        assertTrue(flac.size > PassphraseArchive.CHUNK_BYTES)
        journal.add(JournalKinds.ANSWER, JSONObject().put("promptId", "LS-001").put("prompt", "Tell me about the lake")
            .put("text", "OtterLantern my member number is 88123456 and we rowed all day")
            .put("transcript", "the api_key=sk_live_abcdef123456 is in the drawer")
            .put("child", "Ava").put("audio", JSONArray().put(JSONObject().put("file", id).put("ms", 12_000))), 4_000)
        journal.add(JournalKinds.SETTINGS, JSONObject().put("children", JSONArray().put("Ava").put("Ben")), 5_000)
    }

    @After
    fun tearDown() {
        twin.close(); journal.close(); dir.deleteRecursively()
    }

    private fun export(p: CharArray = pass): Pair<ByteArray, ExportSummary> {
        val out = ByteArrayOutputStream()
        val s = TwinExporter(twin, journal).export(out, p.copyOf(), 1_700_000_000_000, fast)
        return out.toByteArray() to s
    }

    private fun unzip(archive: ByteArray, p: CharArray = pass): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(PassphraseArchive.Reader(archive.inputStream(), TwinExporter.normalize(p))).use { z ->
            while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() }
        }
        return out
    }

    private fun fails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test
    fun exportIsScrubbedCompleteAndVerifiable() {
        val (archive, s) = export()
        assertEquals(2, s.messages)
        assertEquals(1, s.messagesDropped)
        assertEquals(2, s.journalRecords)
        assertEquals(1, s.recordings)
        val m = TwinExporter.verify(archive.inputStream(), pass)
        assertEquals(TwinExporter.FORMAT, m.getString("format"))
        val files = unzip(archive)
        assertEquals(listOf(TwinExporter.MESSAGES, TwinExporter.RECORDS, "audio/$audioId.flac", TwinExporter.MANIFEST), files.keys.toList())
        val messages = files.getValue(TwinExporter.MESSAGES).toString(Charsets.UTF_8)
        assertTrue(messages.contains("ZebraMarmalade see you at the lake"))
        assertFalse(messages.contains("hunter2"))
        assertFalse(messages.contains("0199"))
        val records = files.getValue(TwinExporter.RECORDS).toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map(::JSONObject)
        val answer = records.first { it.getString("kind") == JournalKinds.ANSWER }.getJSONObject("body")
        assertTrue(answer.getString("text").contains("OtterLantern"))
        assertFalse(answer.getString("text").contains("88123456"))
        assertFalse(answer.has("transcript"))
        assertEquals("transcript", answer.getJSONArray("_redacted").getString(0))
        assertEquals("LS-001", answer.getString("promptId"))
        assertEquals(audioId, answer.getJSONArray("audio").getJSONObject(0).getString("file"))
        assertEquals("Ava", answer.getString("child"))
        assertArrayEquals(flac, files.getValue("audio/$audioId.flac"))
    }

    @Test
    fun archiveHoldsOnlyCiphertext() {
        val (archive, _) = export()
        val a = String(archive, Charsets.ISO_8859_1)
        for (n in listOf("ZebraMarmalade", "OtterLantern", "lake", "manifest", "fLaC", "PK\u0003\u0004", "LS-001", "Ava")) {
            assertFalse("archive contains $n", a.contains(n))
        }
        assertEquals("AKTX", a.substring(0, 4))
    }

    private class Chunk(val final: Int, val ct: ByteArray)

    private fun split(archive: ByteArray): Pair<ByteArray, List<Chunk>> {
        val inp = DataInputStream(archive.inputStream())
        val header = ByteArray(34).also(inp::readFully)
        val chunks = ArrayList<Chunk>()
        while (inp.available() > 0) { val f = inp.readUnsignedByte(); chunks += Chunk(f, ByteArray(inp.readInt()).also(inp::readFully)) }
        return header to chunks
    }

    private fun join(header: ByteArray, chunks: List<Chunk>): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).apply { write(header); chunks.forEach { writeByte(it.final); writeInt(it.ct.size); write(it.ct) } }
        return bo.toByteArray()
    }

    @Test
    fun wrongPassphraseAndEveryKindOfTamperingFail() {
        val (archive, _) = export()
        val (header, cs) = split(archive)
        assertTrue(cs.size >= 2 && cs.last().final == 1 && cs.dropLast(1).all { it.final == 0 })
        assertNotNull(TwinExporter.verify(join(header, cs).inputStream(), pass))
        fails { TwinExporter.verify(archive.inputStream(), "correct horse battery stapler".toCharArray()) }
        fails { TwinExporter.verify(archive.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }.inputStream(), pass) } // salt
        fails { TwinExporter.verify(archive.copyOf().also { it[30] = (it[30].toInt() xor 1).toByte() }.inputStream(), pass) } // nonce prefix
        fails { TwinExporter.verify(archive.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }.inputStream(), pass) }
        fails { TwinExporter.verify(join(header, listOf(cs[1], cs[0]) + cs.drop(2)).inputStream(), pass) }
        fails { TwinExporter.verify(join(header, cs.dropLast(1)).inputStream(), pass) }
        fails { TwinExporter.verify(join(header, cs.dropLast(1).mapIndexed { i, c -> if (i == cs.size - 2) Chunk(1, c.ct) else c }).inputStream(), pass) }
        fails { TwinExporter.verify(archive.copyOf(archive.size - 1).inputStream(), pass) }
        fails { TwinExporter.verify((archive + byteArrayOf(0)).inputStream(), pass) }
    }

    @Test
    fun passphraseRulesAndNormalisation() {
        fails { PassphraseArchive.Writer(ByteArrayOutputStream(), "short".toCharArray(), fast) }
        fails { PassphraseArchive.Writer(ByteArrayOutputStream(), pass.copyOf(), 1000) }
        assertEquals("The two passphrases don't match.", TwinExportActivity.problem("abcdefghijkl".toCharArray(), "abcdefghijkm".toCharArray()))
        assertNotNull(TwinExportActivity.problem("short".toCharArray(), "short".toCharArray()))
        assertNotNull(TwinExportActivity.problem("aaaaaaaaaaaaaa".toCharArray(), "aaaaaaaaaaaaaa".toCharArray()))
        assertNull(TwinExportActivity.problem(pass, pass))
        // "café" typed precomposed and decomposed derive the same key.
        val nfc = "café au lait please".toCharArray()
        val nfd = "cafe\u0301 au lait please".toCharArray()
        val (archive, _) = export(nfc)
        assertNotNull(TwinExporter.verify(archive.inputStream(), nfd))
        // The caller's passphrase is wiped by verify.
        val p = pass.copyOf()
        TwinExporter.verify(export().first.inputStream(), p)
    }

    @Test
    fun onlyOnDeviceProvidersAreAccepted() {
        assertTrue(TwinExportActivity.isLocalAuthority("com.android.externalstorage.documents"))
        assertTrue(TwinExportActivity.isLocalAuthority("com.android.providers.downloads.documents"))
        for (a in listOf("com.google.android.apps.docs.storage", "com.microsoft.skydrive.content.StorageAccessProvider",
            "com.dropbox.product.android.dbapp.document_provider.documents", null, "")) {
            assertFalse(a.toString(), TwinExportActivity.isLocalAuthority(a))
        }
    }

    @Test
    fun emptyStoresStillExport() {
        val out = ByteArrayOutputStream()
        val s = TwinExporter(null, null).export(out, pass.copyOf(), 1, fast)
        assertEquals(0, s.messages + s.journalRecords + s.recordings)
        assertEquals(2, TwinExporter.verify(out.toByteArray().inputStream(), pass).getJSONObject("files").length())
    }

    @Test
    fun pythonDecryptScriptOpensTheAppsArchive() {
        val script = listOf(File("../tools/twin-export/decrypt_twin_export.py"), File("tools/twin-export/decrypt_twin_export.py")).firstOrNull { it.exists() }
        assertNotNull("decrypt script is in the repository", script)
        val ok = runCatching { ProcessBuilder("python3", "-c", "import cryptography").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("python3 with cryptography not installed", ok)
        val tmp = kotlin.io.path.createTempDirectory("aktx").toFile()
        try {
            val file = File(tmp, "x.aktx").apply { writeBytes(export().first) }
            fun run(vararg args: String, passphrase: String = String(pass)): Pair<Int, String> {
                val pb = ProcessBuilder(listOf("python3", script!!.path) + args).redirectErrorStream(true)
                pb.environment()["TWIN_EXPORT_PASSPHRASE"] = passphrase
                val p = pb.start()
                val log = p.inputStream.bufferedReader().readText()
                return p.waitFor() to log
            }
            val (code, log) = run(file.path, File(tmp, "out").path)
            assertEquals(log, 0, code)
            assertArrayEquals(flac, File(tmp, "out/audio/$audioId.flac").readBytes())
            assertTrue(File(tmp, "out/twin/messages.jsonl").readText().contains("ZebraMarmalade"))
            assertEquals(2, run("--verify-only", file.path, passphrase = "wrong passphrase!!").first)
            val bad = File(tmp, "bad.aktx").apply { writeBytes(file.readBytes().copyOf(file.length().toInt() - 3)) }
            assertEquals(2, run("--verify-only", bad.path).first)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
