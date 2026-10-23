package io.github.daddymean.agentickeyboard.twin.capsule

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import io.github.daddymean.agentickeyboard.twin.TwinStore
import io.github.daddymean.agentickeyboard.twin.TwinVault
import io.github.daddymean.agentickeyboard.twin.deadman.DeadManSwitch
import io.github.daddymean.agentickeyboard.twin.export.PassphraseArchive
import io.github.daddymean.agentickeyboard.twin.export.TwinExporter
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
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

/** KEYBOARD-036: time capsules sealed on the phone, in both archives, and opened by two holders' cards. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TimeCapsuleTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var cipher: SoftwareTwinCipher
    private lateinit var twin: TwinStore
    private lateinit var journal: JournalStore
    private lateinit var store: CapsuleStore
    private val today = LocalDate.of(2026, 10, 19)
    private val flac = "fLaC-PelicanHarbor".toByteArray()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.noBackupFilesDir, "twin-capsule-test").also { it.deleteRecursively() }
        cipher = SoftwareTwinCipher()
        twin = TwinStore(context, File(dir, "twin_store.db"), cipher)
        journal = JournalStore(context, dir, cipher)
        store = CapsuleStore(File(dir, "capsules"), cipher)
    }

    @After
    fun tearDown() { twin.close(); journal.close(); dir.deleteRecursively() }

    private fun wedding() = TimeCapsules.create("Kid 2", "For your wedding day", Unlock(event = "Wedding"), "MarigoldCompass: be kind to each other.",
        listOf(CapsuleAudio("take-1", 4200, flac)), 1_760_000_000_000)
    private fun eighteen() = TimeCapsules.create("Kid 5", "At 18", Unlock.eighteenth(LocalDate.of(2012, 3, 4)), "HeronLantern: you made it.", emptyList(), 1_760_000_000_001)

    @Test
    fun capsulesAreValidatedAndSealedOnThePhone() {
        assertThrows(IllegalArgumentException::class.java) { TimeCapsules.create("Guardian 1", "x", Unlock(event = "Wedding"), "hi", emptyList(), 1) }
        assertThrows(IllegalArgumentException::class.java) { TimeCapsules.create("Kid 1", "", Unlock(event = "Wedding"), "hi", emptyList(), 1) }
        assertThrows(IllegalArgumentException::class.java) { TimeCapsules.create("Kid 1", "t", Unlock(event = "Wedding"), " ", emptyList(), 1) }
        assertThrows(IllegalArgumentException::class.java) { Unlock(date = today, event = "Wedding") }
        assertEquals(LocalDate.of(2030, 3, 4), eighteen().unlock.date)
        val c = wedding()
        store.save(c)
        val raw = File(dir, "capsules").listFiles()!!.joinToString { String(it.readBytes(), Charsets.ISO_8859_1) }
        for (n in listOf("MarigoldCompass", "wedding", "Kid 2", "PelicanHarbor")) assertFalse(n, raw.contains(n))
        val back = store.all().single()
        assertEquals(c.text, back.text)
        assertArrayEquals(flac, back.audio.single().flac)
        // Delete-all removes them.
        TwinVault.deleteAll(journal, twin, null, null, null, store)
        assertTrue(store.all().isEmpty())
    }

    private fun entries(bytes: ByteArray, open: (java.io.InputStream, java.io.OutputStream) -> Unit): Map<String, ByteArray> {
        val zip = ByteArrayOutputStream()
        open(bytes.inputStream(), zip)
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zip.toByteArray().inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() } }
        return out
    }

    @Test
    fun keithsExportHasThemReadableAndTheReleaseHasThemSealed() {
        store.save(wedding()); store.save(eighteen())
        // Keith's own export: readable, flagged "not training data", with the FLAC.
        val mine = ByteArrayOutputStream().also { TwinExporter(twin, journal, null, store).export(it, "correct horse battery".toCharArray(), 1, iterations = 100_000) }.toByteArray()
        val m = entries(mine) { i, o -> PassphraseArchive.Reader(i, "correct horse battery".toCharArray()).use { it.copyTo(o) } }
        val w = m.entries.single { it.key.endsWith(".json") && String(it.value).contains("wedding") }
        assertTrue(String(w.value).contains("MarigoldCompass") && JSONObject(String(w.value)).getBoolean("for_kids_not_training"))
        assertArrayEquals(flac, m.entries.single { it.key.endsWith("take-1.flac") }.value)
        assertEquals(2, JSONObject(String(m["manifest.json"]!!)).getJSONObject("counts").getInt("capsules"))

        // The release: only kid, title and rule are visible; the content is sealed per capsule.
        val dm = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today)
        val rel = ByteArrayOutputStream().also {
            TwinExporter(twin, journal, null, store).exportRelease(it, PassphraseArchive.dataKeyParams(dm.state.dataKey, dm.state.archiveId), 1, dm.state.dataKey, dm.state.archiveId)
        }.toByteArray()
        val r = entries(rel) { i, o -> PassphraseArchive.Reader(i, dm.state.dataKey).use { it.copyTo(o) } }
        val sealed = r.filterKeys { it.startsWith("capsules/") }
        assertEquals(2, sealed.size)
        for ((_, v) in sealed) {
            val t = String(v)
            assertFalse(t.contains("MarigoldCompass") || t.contains("HeronLantern") || t.contains("PelicanHarbor"))
        }
        assertTrue(JSONObject(String(r["manifest.json"]!!)).getJSONObject("counts").getBoolean("capsules_sealed"))
        // Two cards open one capsule, and still do after a holder change (the DEK doesn't change).
        val entry = JSONObject(String(sealed.values.first { String(it).contains("wedding") }))
        val second = DeadManSwitch.replaceHolder(dm.state, 1, "Kid 1", LocalDate.of(2008, 1, 1), today)
        val dek = DeadManSwitch.unwrap(second.state.keyFile, second.cards.take(2).map { it.second })
        assertTrue(TimeCapsules.openReleased(entry, dek, dm.state.archiveId).getString("text").contains("MarigoldCompass"))
        // A different archive's key, or a moved kid label, fails.
        assertThrows(Exception::class.java) { TimeCapsules.openReleased(entry, ByteArray(32), dm.state.archiveId) }
        assertThrows(Exception::class.java) { TimeCapsules.openReleased(JSONObject(entry.toString()).put("kid", "Kid 3"), dek, dm.state.archiveId) }
        // A release without its key leaves capsules out rather than writing them in the clear.
        val noKey = ByteArrayOutputStream().also {
            TwinExporter(twin, journal, null, store).exportRelease(it, PassphraseArchive.dataKeyParams(dm.state.dataKey, dm.state.archiveId), 1)
        }.toByteArray()
        assertTrue(entries(noKey) { i, o -> PassphraseArchive.Reader(i, dm.state.dataKey).use { it.copyTo(o) } }.keys.none { it.startsWith("capsules/") })
    }

    @Test
    fun offDeviceToolOpensOneCapsuleUnderItsRule() {
        val script = listOf(File("../tools/twin-export/open_time_capsule.py"), File("tools/twin-export/open_time_capsule.py")).firstOrNull { it.exists() }
        assertNotNull(script)
        val ok = runCatching { ProcessBuilder("python3", "-c", "import cryptography").start().waitFor() == 0 }.getOrDefault(false)
        assumeTrue("python3 with cryptography not installed", ok)
        val dm = DeadManSwitch.create(DeadManSwitch.GUARDIANS, today)
        val tmp = kotlin.io.path.createTempDirectory("akcap").toFile()
        try {
            val key = File(tmp, "key.json").apply { writeText(dm.state.keyFile.toString(2)) }
            val w = File(tmp, "w.json").apply { writeText(TimeCapsules.sealForRelease(wedding(), dm.state.dataKey, dm.state.archiveId).toString(2)) }
            val e = File(tmp, "e.json").apply { writeText(TimeCapsules.sealForRelease(eighteen(), dm.state.dataKey, dm.state.archiveId).toString(2)) }
            val cards = dm.cards.map { it.second.encode() }
            fun run(vararg args: String): Pair<Int, String> {
                val p = ProcessBuilder(listOf("python3", script!!.path) + args).redirectErrorStream(true).start()
                val log = p.inputStream.bufferedReader().readText()
                return p.waitFor() to log
            }
            val out = File(tmp, "out").path
            // Event: needs two different holders confirming.
            assertEquals(2, run(w.path, key.path, out, "--card", cards[0], "--card", cards[1]).first)
            assertEquals(2, run(w.path, key.path, out, "--card", cards[0], "--card", cards[1], "--confirm", "Guardian 1", "--confirm", "Kid 9").first)
            val (code, log) = run(w.path, key.path, out, "--card", cards[0], "--card", cards[2], "--confirm", "Guardian 1", "--confirm", "Guardian 3")
            assertEquals(log, 0, code)
            val opened = File(out).listFiles()!!.single()
            assertTrue(File(opened, "message.md").readText().contains("MarigoldCompass"))
            assertArrayEquals(flac, File(opened, "take-1.flac").readBytes())
            // Date: not before the 18th birthday; one card is never enough.
            assertEquals(2, run(e.path, key.path, out, "--card", cards[0], "--card", cards[1], "--today", "2030-03-03").first)
            assertEquals(2, run(e.path, key.path, out, "--card", cards[0], "--today", "2030-03-04").first)
            assertEquals(0, run(e.path, key.path, out, "--card", cards[0], "--card", cards[1], "--today", "2030-03-04").first)
        } finally {
            tmp.deleteRecursively()
        }
    }
}
