package io.github.daddymean.agentickeyboard.twin.deadman

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.DeadManActivity
import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import io.github.daddymean.agentickeyboard.twin.TwinStore
import io.github.daddymean.agentickeyboard.twin.TwinVault
import io.github.daddymean.agentickeyboard.twin.export.PassphraseArchive
import io.github.daddymean.agentickeyboard.twin.export.TwinExporter
import io.github.daddymean.agentickeyboard.twin.journal.JournalKinds
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore
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
import java.io.File
import java.time.LocalDate

/** KEYBOARD-027 rows 2, 4, 6 and 7: the release archive, the sealed state, delete-all and the off-device tool. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeadManReleaseTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var cipher: SoftwareTwinCipher
    private lateinit var twin: TwinStore
    private lateinit var journal: JournalStore
    private val today = LocalDate.of(2026, 10, 17)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.noBackupFilesDir, "twin-deadman-test").also { it.deleteRecursively() }
        cipher = SoftwareTwinCipher()
        twin = TwinStore(context, File(dir, "twin_store.db"), cipher)
        journal = JournalStore(context, dir, cipher)
        twin.add("ZebraMarmalade I love you all more than you know", "com.example.chat", 1_000)
        twin.add("my password is hunter2", "com.example.chat", 2_000)
        journal.add(JournalKinds.ANSWER, JSONObject().put("promptId", "LS-001").put("text", "OtterLantern we built the treehouse"), 3_000)
    }

    @After
    fun tearDown() { twin.close(); journal.close(); dir.deleteRecursively() }

    private fun archive(s: DeadManState): ByteArray = ByteArrayOutputStream().also {
        TwinExporter(twin, journal).exportRelease(it, PassphraseArchive.dataKeyParams(s.dataKey, s.archiveId), 1_700_000_000_000)
    }.toByteArray()

    private fun fails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test
    fun releaseArchiveIsScrubbedCiphertextAndSurvivesRekeyUnchanged() {
        val first = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today)
        val bytes = archive(first.state)
        val a = String(bytes, Charsets.ISO_8859_1)
        for (n in listOf("ZebraMarmalade", "OtterLantern", "treehouse", "manifest.json")) assertFalse(a.contains(n))
        assertArrayEquals(first.state.archiveId, PassphraseArchive.archiveId(bytes.inputStream()))
        val m = TwinExporter.verifyRelease(bytes.inputStream(), first.state.dataKey)
        assertEquals(1, m.getJSONObject("counts").getInt("messages"))
        assertEquals(1, m.getJSONObject("counts").getInt("messages_dropped"))
        // A passphrase can't open it, and a wrong key fails.
        fails { TwinExporter.verify(bytes.inputStream(), "correct horse battery staple".toCharArray()) }
        fails { TwinExporter.verifyRelease(bytes.inputStream(), ByteArray(32)) }
        fails { TwinExporter.verifyRelease(bytes.copyOf().also { it[it.size - 30] = (it[it.size - 30].toInt() xor 1).toByte() }.inputStream(), first.state.dataKey) }
        // After a holder change the same bytes open with the new cards' key.
        val second = DeadManSwitch.replaceHolder(first.state, 1, "Kid 1", LocalDate.of(2008, 1, 1), today)
        val dek = DeadManSwitch.unwrap(second.state.keyFile, second.cards.drop(1).map { it.second })
        assertNotNull(TwinExporter.verifyRelease(bytes.inputStream(), dek))
    }

    @Test
    fun stateIsSealedAndDeleteAllRemovesIt() {
        val store = DeadManStore(File(dir, "deadman.sealed"), cipher)
        assertNull(store.load())
        val s = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today).state
        store.save(s)
        val raw = String(File(dir, "deadman.sealed").readBytes(), Charsets.ISO_8859_1)
        for (n in listOf(hex(s.dataKey), hex(s.kek!!), "Guardian", s.setId, "dek", "kek", "release")) assertFalse(n, raw.contains(n))
        assertEquals(s, store.load())
        TwinVault.deleteAll(journal, twin, store)
        assertFalse(File(dir, "deadman.sealed").exists())
        assertEquals(1, cipher.destroyed)
        assertNull(store.load())
    }

    @Test
    fun cardTextIsSelfExplaining() {
        val t = DeadManActivity.cardText("Guardian 2", "AKDM1-X", 3)
        assertTrue(t.contains("Guardian 2 (set 3)") && t.contains("6 months") && t.contains("open_twin_release.py"))
    }

    @Test
    fun offDeviceToolRecombinesCardsAndOpensTheArchive() {
        val script = listOf(File("../tools/twin-export/open_twin_release.py"), File("tools/twin-export/open_twin_release.py")).firstOrNull { it.exists() }
        assertNotNull(script)
        val ok = runCatching { ProcessBuilder("python3", "-c", "import cryptography").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("python3 with cryptography not installed", ok)
        val first = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today)
        val second = DeadManSwitch.replaceHolder(first.state, 2, "Kid 1", LocalDate.of(2008, 10, 17), today)
        val tmp = kotlin.io.path.createTempDirectory("akdm").toFile()
        try {
            val arc = File(tmp, "twin-release.aktx").apply { writeBytes(archive(second.state)) }
            val key1 = File(tmp, "key-set1.json").apply { writeText(first.state.keyFile.toString(2)) }
            val key2 = File(tmp, "key-set2.json").apply { writeText(second.state.keyFile.toString(2)) }
            val newCards = second.cards.map { (h, c) -> File(tmp, "new-${h.x}.txt").apply { writeText(DeadManActivity.cardText(h.label, c.encode(), 2)) } }
            val oldCards = first.cards.map { (h, c) -> File(tmp, "old-${h.x}.txt").apply { writeText(c.encode()) } }
            fun run(vararg args: String): Pair<Int, String> {
                val p = ProcessBuilder(listOf("python3", script!!.path) + args).redirectErrorStream(true).start()
                val log = p.inputStream.bufferedReader().readText()
                return p.waitFor() to log
            }
            val (code, log) = run(arc.path, key2.path, "--card", newCards[0].path, "--card", second.cards[1].second.encode().lowercase(), File(tmp, "out").path)
            assertEquals(log, 0, code)
            assertTrue(log, log.contains("Kid 1"))
            assertTrue(File(tmp, "out/twin/messages.jsonl").readText().contains("ZebraMarmalade"))
            assertFalse(File(tmp, "out/twin/messages.jsonl").readText().contains("hunter2"))
            // One card, old cards with the new key file, mixed sets: all refused.
            assertEquals(2, run("--verify-only", arc.path, key2.path, "--card", newCards[2].path).first)
            assertEquals(2, run("--verify-only", arc.path, key2.path, "--card", oldCards[0].path, "--card", oldCards[1].path).first)
            assertEquals(2, run("--verify-only", arc.path, key2.path, "--card", oldCards[0].path, "--card", newCards[1].path).first)
            // The documented residual: an old key file with old cards still opens (old key files must be destroyed).
            assertEquals(0, run("--verify-only", arc.path, key1.path, "--card", oldCards[0].path, "--card", oldCards[2].path).first)
            // KEYBOARD-039: the next archive has its own key. New cards open it; the old key file and
            // old cards don't, even with the archive id rewritten.
            val next = DeadManSwitch.newArchive(second.state)
            val arc3 = File(tmp, "twin-release-next.aktx").apply { writeBytes(archive(next)) }
            val key3 = File(tmp, "key-next.json").apply { writeText(next.keyFile.toString(2)) }
            assertEquals(0, run("--verify-only", arc3.path, key3.path, "--card", newCards[0].path, "--card", newCards[1].path).first)
            val key1Pointed = File(tmp, "key-set1-pointed.json").apply {
                writeText(JSONObject(first.state.keyFile.toString()).put("archive_id", hex(next.archiveId)).toString(2))
            }
            assertEquals(2, run("--verify-only", arc3.path, key1.path, "--card", oldCards[0].path, "--card", oldCards[2].path).first)
            assertEquals(2, run("--verify-only", arc3.path, key1Pointed.path, "--card", oldCards[0].path, "--card", oldCards[2].path).first)
            // Wrong archive for the key file.
            val other = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today)
            val arc2 = File(tmp, "other.aktx").apply { writeBytes(archive(other.state)) }
            assertEquals(2, run("--verify-only", arc2.path, key2.path, "--card", newCards[0].path, "--card", newCards[2].path).first)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
