package io.github.daddymean.agentickeyboard.twin.export

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.DeadManActivity
import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import io.github.daddymean.agentickeyboard.twin.deadman.DeadManSwitch
import io.github.daddymean.agentickeyboard.twin.journal.JournalKinds
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore
import io.github.daddymean.agentickeyboard.twin.memory.LegacyVault
import io.github.daddymean.agentickeyboard.twin.memory.Memory
import io.github.daddymean.agentickeyboard.twin.memory.MemoryStore
import io.github.daddymean.agentickeyboard.twin.memory.SourceRef
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream

/** KEYBOARD-032: approved and core memories in the export and the release archive, with sources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwinExportMemoryTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var cipher: SoftwareTwinCipher
    private lateinit var journal: JournalStore
    private lateinit var memory: MemoryStore
    private val pass = "correct horse battery staple".toCharArray()
    private val fast = 100_000
    private var answerId = 0L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.noBackupFilesDir, "twin-export-memory-test").also { it.deleteRecursively() }
        cipher = SoftwareTwinCipher()
        journal = JournalStore(context, dir, cipher)
        memory = MemoryStore(File(dir, "memory.sealed"), cipher)
        answerId = journal.add(JournalKinds.ANSWER, JSONObject().put("promptId", "W-1").put("prompt", "What should the kids know about work?")
            .put("text", "Come home for dinner, always.").put("transcript", "PendingWalrus is still waiting for review.")
            .put("child", "Kid 1").put("audio", JSONArray()), 1_760_000_000_000)
        val rejectedId = journal.add(JournalKinds.ANSWER, JSONObject().put("prompt", "Q").put("text", "RejectedHeron never goes out."), 1_760_000_100_000)
        memory.update { s0 ->
            val cands = LegacyVault.candidates(journal.all(), s0)
            var s = LegacyVault.approve(s0, cands.single { it.source.key == "journal:$answerId:text" }, 1_760_000_200_000, id = "m-dinner")
            s = LegacyVault.reject(s, cands.single { it.source.recordId == rejectedId })
            // A memory whose (edited) text still holds a number, and one that turned into a credential: the export scrubs again.
            s = s.copy(memories = s.memories +
                Memory("m-number", "Our boat club number was 88123456.", SourceRef(answerId, "transcript"), "x", "Q", null, 1, 2, true) +
                Memory("m-secret", "my password is hunter2", SourceRef(answerId, "transcript"), "x", null, null, 1, 2, true))
            s = LegacyVault.setCore(s, LegacyVault.WHO_I_AM, "I'm Keith, born in 1980 in Fresno. I fix things.", 3)
            LegacyVault.setCore(s, LegacyVault.ABOUT_MY_KIDS, "Six kids. The wifi password is hunter2hunter2.", 4)
        }
    }

    @After fun tearDown() { journal.close(); dir.deleteRecursively() }

    private fun unzip(archive: ByteArray, reader: (ByteArray) -> java.io.InputStream): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(reader(archive)).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() } }
        return out
    }

    private fun checkMemories(files: Map<String, ByteArray>) {
        val mems = String(files.getValue(TwinExporter.MEMORIES)).lines().filter { it.isNotBlank() }.map(::JSONObject)
        assertEquals(listOf("m:m-dinner", "m:m-number"), mems.map { it.getString("cite") })
        val dinner = mems[0]
        assertEquals("Come home for dinner, always.", dinner.getString("text"))
        val src = dinner.getJSONObject("source")
        assertEquals(answerId, src.getLong("journal_record_id"))
        assertEquals("text", src.getString("field"))
        assertEquals("What should the kids know about work?", src.getString("prompt"))
        // The cited record is in the same archive.
        val records = String(files.getValue(TwinExporter.RECORDS)).lines().filter { it.isNotBlank() }.map { JSONObject(it).getLong("id") }
        assertTrue(answerId in records)
        assertFalse("export scrubs memory text again", mems[1].getString("text").contains("88123456"))
        val all = String(files.getValue(TwinExporter.MEMORIES))
        assertFalse(all.contains("hunter2"))
        assertFalse("pending candidates stay out", all.contains("PendingWalrus"))
        assertFalse("rejected stays out", all.contains("RejectedHeron"))
        // Core: Keith's own words, not identifier-scrubbed; a block with a credential is withheld.
        val core = JSONObject(String(files.getValue(TwinExporter.CORE)))
        assertFalse(core.getBoolean("scrubbed"))
        val blocks = core.getJSONArray("blocks")
        assertEquals(1, blocks.length())
        assertEquals("core:who_i_am", blocks.getJSONObject(0).getString("cite"))
        assertEquals("I'm Keith, born in 1980 in Fresno. I fix things.", blocks.getJSONObject(0).getString("value"))
        assertFalse(String(files.getValue(TwinExporter.CORE)).contains("hunter2"))
        // The manifest hashes and counts them.
        val manifest = JSONObject(String(files.getValue(TwinExporter.MANIFEST)))
        val f = manifest.getJSONObject("files")
        assertTrue(f.has(TwinExporter.MEMORIES) && f.has(TwinExporter.CORE))
        val c = manifest.getJSONObject("counts")
        assertEquals(2, c.getInt("memories")); assertEquals(1, c.getInt("memories_dropped"))
        assertEquals(1, c.getInt("core_blocks")); assertEquals(1, c.getInt("core_blocks_withheld"))
    }

    @Test
    fun exportCarriesApprovedAndCoreMemoriesWithSources() {
        val out = ByteArrayOutputStream()
        val s = TwinExporter(null, journal, memory).export(out, pass.copyOf(), 1_700_000_000_000, fast)
        assertEquals(2, s.memories); assertEquals(1, s.memoriesDropped); assertEquals(1, s.coreBlocks); assertEquals(1, s.coreBlocksWithheld)
        TwinExporter.verify(out.toByteArray().inputStream(), pass)
        checkMemories(unzip(out.toByteArray()) { PassphraseArchive.Reader(it.inputStream(), TwinExporter.normalize(pass)) })
        assertFalse(String(out.toByteArray(), Charsets.ISO_8859_1).contains("Fresno"))
    }

    @Test
    fun releaseArchiveCarriesThemToo() {
        val r = DeadManSwitch.create(DeadManSwitch.GUARDIANS, LocalDate.of(2026, 10, 19))
        val out = ByteArrayOutputStream()
        TwinExporter(null, journal, memory).exportRelease(out, PassphraseArchive.dataKeyParams(r.state.dataKey, r.state.archiveId), 1)
        TwinExporter.verifyRelease(out.toByteArray().inputStream(), r.state.dataKey)
        checkMemories(unzip(out.toByteArray()) { PassphraseArchive.Reader(it.inputStream(), r.state.dataKey) })
    }

    @Test
    fun toolsUnpackReadableMemoryFiles() {
        val tools = listOf(File("../tools/twin-export"), File("tools/twin-export")).first { it.isDirectory }
        val ok = runCatching { ProcessBuilder("python3", "-c", "import cryptography").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("python3 with cryptography not installed", ok)
        val tmp = kotlin.io.path.createTempDirectory("aktxmem").toFile()
        try {
            fun run(env: Map<String, String>, vararg args: String): Pair<Int, String> {
                val pb = ProcessBuilder(listOf("python3") + args).redirectErrorStream(true)
                pb.environment().putAll(env)
                val p = pb.start(); val log = p.inputStream.bufferedReader().readText(); return p.waitFor() to log
            }
            fun checkOut(out: File, log: String) {
                val md = File(out, "memory/memories.md").readText()
                assertTrue(md, md.contains("[m:m-dinner]"))
                assertTrue(md.contains("> Come home for dinner, always."))
                assertTrue(md.contains("Source: journal record #$answerId, field `text`"))
                assertFalse(md.contains("record not in this archive"))
                assertTrue(md.contains("*Question:* What should the kids know about work?"))
                val core = File(out, "memory/core.md").readText()
                assertTrue(core.contains("## Who I am [core:who_i_am]"))
                assertTrue(core.contains("born in 1980 in Fresno"))
                assertFalse(core.contains("hunter2"))
                assertTrue(log, log.contains("2 approved memories (1 dropped by the scrubber), 1 core memories (1 withheld)"))
            }
            // Passphrase export.
            val a = File(tmp, "x.aktx").apply { outputStream().use { TwinExporter(null, journal, memory).export(it, pass.copyOf(), 1, fast) } }
            val (code, log) = run(mapOf("TWIN_EXPORT_PASSPHRASE" to String(pass)), File(tools, "decrypt_twin_export.py").path, a.path, File(tmp, "out").path)
            assertEquals(log, 0, code)
            checkOut(File(tmp, "out"), log)
            // Dead-man release.
            val r = DeadManSwitch.create(DeadManSwitch.GUARDIANS, LocalDate.of(2026, 10, 19))
            val rel = File(tmp, "r.aktx").apply { outputStream().use { TwinExporter(null, journal, memory).exportRelease(it, PassphraseArchive.dataKeyParams(r.state.dataKey, r.state.archiveId), 1) } }
            val key = File(tmp, "key.json").apply { writeText(r.state.keyFile.toString(2)) }
            val cards = r.cards.take(2).mapIndexed { i, (h, c) -> File(tmp, "card$i.txt").apply { writeText(DeadManActivity.cardText(h.label, c.encode(), 1)) } }
            val (code2, log2) = run(emptyMap(), File(tools, "open_twin_release.py").path, rel.path, key.path, "--card", cards[0].path, "--card", cards[1].path, File(tmp, "rel").path)
            assertEquals(log2, 0, code2)
            checkOut(File(tmp, "rel"), log2)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
