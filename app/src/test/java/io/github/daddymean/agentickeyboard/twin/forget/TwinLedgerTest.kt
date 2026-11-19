package io.github.daddymean.agentickeyboard.twin.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.twin.LearnedGroup
import io.github.daddymean.agentickeyboard.twin.NeverLearnLists
import io.github.daddymean.agentickeyboard.twin.NeverLearnPhrases
import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import io.github.daddymean.agentickeyboard.twin.TwinLedger
import io.github.daddymean.agentickeyboard.twin.TwinStore
import io.github.daddymean.agentickeyboard.twin.TwinStyleStats
import io.github.daddymean.agentickeyboard.twin.TwinVault
import io.github.daddymean.agentickeyboard.twin.deadman.DeadManSwitch
import io.github.daddymean.agentickeyboard.twin.export.PassphraseArchive
import io.github.daddymean.agentickeyboard.twin.export.TwinExporter
import io.github.daddymean.agentickeyboard.twin.journal.JournalKinds
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore
import io.github.daddymean.agentickeyboard.twin.memory.EncryptedVectorIndex
import io.github.daddymean.agentickeyboard.twin.memory.LegacyVault
import io.github.daddymean.agentickeyboard.twin.memory.MemoryIndex
import io.github.daddymean.agentickeyboard.twin.memory.MemoryStore
import io.github.daddymean.agentickeyboard.twin.memory.SemanticMemorySearch
import io.github.daddymean.agentickeyboard.twin.memory.TextEmbedder
import io.github.daddymean.agentickeyboard.twin.memory.TwinAnswerer
import io.github.daddymean.agentickeyboard.twin.memory.VaultState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream

/**
 * Twin-memory viewer: listing, search, and a delete that reaches every store (typed
 * messages, the vault, core blocks, the journal, the FTS index, the embedding vectors),
 * that later exports and dead-man archives honour, and that no re-index brings back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwinLedgerTest {
    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var cipher: SoftwareTwinCipher
    private lateinit var twin: TwinStore
    private lateinit var journal: JournalStore
    private lateinit var memory: MemoryStore
    private lateinit var vectors: EncryptedVectorIndex
    private val apps = LinkedHashSet<String>()
    private val phrases = LinkedHashSet<String>()
    private val lists = object : NeverLearnLists {
        override fun addApp(packageName: String) { apps += packageName }
        override fun addPhrase(phrase: String): Boolean = NeverLearnPhrases.entry(phrase, key)?.let { phrases += it; true } ?: false
    }
    private val key = FakePhraseKey()
    private val pass = "correct horse battery staple".toCharArray()
    private var fishRecord = 0L
    private var dinnerRecord = 0L

    private val day = 86_400_000L
    private val t0 = 1_760_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.noBackupFilesDir, "twin-ledger-test").also { it.deleteRecursively(); it.mkdirs() }
        cipher = SoftwareTwinCipher()
        twin = TwinStore(context, File(dir, "twin_store.db"), cipher)
        journal = JournalStore(context, dir, cipher)
        memory = MemoryStore(File(dir, "memory.sealed"), cipher)
        vectors = EncryptedVectorIndex(File(dir, "memory-vectors.sealed"), cipher)

        twin.add("See you at the lake, Kingfisher crew", "com.whatsapp", t0)
        twin.add("Running late, see you at the lake soon", "com.whatsapp", t0 + day)
        twin.add("Quarterly numbers look ZebraFinch good", "com.slack", t0 + 2 * day)
        twin.add("Ok sounds good, see you at the lake", "com.google.android.apps.messaging", t0 + 5 * day)

        fishRecord = journal.add(JournalKinds.ANSWER, JSONObject().put("prompt", "What did fishing teach you?")
            .put("text", "Fishing at the lake with Grandpa taught me OspreyPatience.").put("audio", JSONArray()), t0)
        dinnerRecord = journal.add(JournalKinds.ANSWER, JSONObject().put("prompt", "What about work?")
            .put("text", "Always come home for dinner, HeronSupper.").put("audio", JSONArray()), t0 + day)
        memory.update { s0 ->
            val c = LegacyVault.candidates(journal.all(), s0)
            var s = LegacyVault.approve(s0, c.single { it.source.recordId == fishRecord }, t0 + 3 * day, id = "m-fish")
            s = LegacyVault.approve(s, c.single { it.source.recordId == dinnerRecord }, t0 + 4 * day, id = "m-dinner")
            LegacyVault.setCore(s, LegacyVault.WHO_I_AM, "I'm Keith and I fix things, CoreMagpie.", t0)
        }
        // Vectors exist for both memories, as after EmbeddingGemma was used once.
        searcher(memory.load()).use { it.sync(memory.load()) }
        assertEquals(setOf("m-fish", "m-dinner"), vectors.load().entries.map { it.memoryId }.toSet())
    }

    @After
    fun tearDown() { twin.close(); journal.close(); dir.deleteRecursively() }

    private fun ledger() = TwinLedger(twin, memory, journal, vectors, lists, clock = { t0 + 10 * day })

    private fun searcher(state: VaultState, embedder: TextEmbedder? = HashEmbedder) = SemanticMemorySearch(
        vectors, MemoryIndex.build(context, state, allowMainThread = true), state.memories, embedder, "test-model"
    )

    // ---- listing and search ------------------------------------------------------------------

    @Test
    fun listsEveryStoreGroupedWithSourceDateAndWhy() {
        val items = ledger().list()
        val byGroup = items.groupBy { it.group }
        assertEquals(setOf("m:m-fish", "m:m-dinner"), byGroup.getValue(LearnedGroup.MEMORIES).map { it.key }.toSet())
        assertEquals(listOf("core:${LegacyVault.WHO_I_AM}"), byGroup.getValue(LearnedGroup.CORE).map { it.key })
        assertEquals(4, byGroup.getValue(LearnedGroup.MESSAGES).size)
        assertEquals(2, byGroup.getValue(LearnedGroup.JOURNAL).size)
        assertTrue(byGroup.getValue(LearnedGroup.PHRASES).any { it.text == "see you at" })

        val msg = byGroup.getValue(LearnedGroup.MESSAGES).first()
        assertEquals("newest first", t0 + 5 * day, msg.atMillis)
        assertEquals("com.google.android.apps.messaging", msg.sourcePackage)
        assertTrue(msg.why!!.contains("typed and sent"))
        val fish = items.single { it.key == "m:m-fish" }
        assertEquals(t0 + 3 * day, fish.atMillis)
        assertTrue(fish.why!!.contains("journal answer #$fishRecord") && fish.why!!.contains("What did fishing teach you?"))
        assertEquals(listOf("com.whatsapp" to 2, "com.slack" to 1, "com.google.android.apps.messaging" to 1).toSet(), TwinLedger.apps(items).toSet())
    }

    @Test
    fun searchFoldsCaseAndAccentsAndMatchesTheApp() {
        val items = ledger().list()
        assertEquals(listOf("m:m-fish"), TwinLedger.search(items, "ÓSPREYpat").filter { it.group == LearnedGroup.MEMORIES }.map { it.key })
        assertEquals(1, TwinLedger.search(items, "slack").size)
        assertEquals(items, TwinLedger.search(items, "  "))
        assertTrue(TwinLedger.search(items, "lake kingfisher").all { it.text.contains("Kingfisher") })
        assertTrue(TwinLedger.search(items, "nothingmatchesthis").isEmpty())
    }

    // ---- cascade -----------------------------------------------------------------------------

    @Test
    fun deletingAMemoryReachesVaultFtsVectorsCitationsAndReview() {
        val gen = TwinLedger.generation
        val r = ledger().forgetKeys(listOf("m:m-fish"))
        assertEquals(1, r.memories); assertEquals(1, r.vectors)
        assertTrue(TwinLedger.generation > gen)

        val state = memory.load()
        assertEquals(listOf("m-dinner"), state.memories.map { it.id })
        assertEquals(listOf("m-dinner"), vectors.load().entries.map { it.memoryId })
        // FTS4 index rebuilt from the vault, keyword search and cited answers: gone.
        MemoryIndex.build(context, state, allowMainThread = true).use { idx ->
            assertTrue(idx.search("OspreyPatience").isEmpty())
            val reply = TwinAnswerer.answer("What did fishing with Grandpa teach you?", state, idx)
            assertTrue(reply.citations.none { it.ref == "m:m-fish" })
            assertFalse(reply.render().contains("OspreyPatience"))
        }
        // The journal answer is not offered for review again.
        assertTrue(LegacyVault.candidates(journal.all(), state).none { it.source.recordId == fishRecord })
    }

    @Test
    fun vectorsArePrunedEvenWithoutTheEmbeddingModel() {
        // No model on the phone: the old sync returned early and left the vector behind.
        memory.update { LegacyVault.remove(it, "m-fish") }
        searcher(memory.load(), embedder = null).use { it.sync(memory.load()) }
        assertEquals(listOf("m-dinner"), vectors.load().entries.map { it.memoryId })
    }

    @Test
    fun deletingAJournalAnswerTakesItsMemoriesAndVectors() {
        val r = ledger().forgetKeys(listOf("journal:$dinnerRecord"))
        assertEquals(1, r.journalRecords); assertEquals(1, r.derivedMemories); assertEquals(1, r.vectors)
        assertTrue(journal.all().none { it.id == dinnerRecord })
        assertEquals(listOf("m-fish"), memory.load().memories.map { it.id })
        assertEquals(listOf("m-fish"), vectors.load().entries.map { it.memoryId })
    }

    @Test
    fun journalScreenDeleteAlsoPrunesVectors() {
        TwinVault.deleteJournalRecord(journal, memory, fishRecord, vectors)
        assertEquals(listOf("m-dinner"), vectors.load().entries.map { it.memoryId })
    }

    @Test
    fun multiSelectDeletesAcrossStores() {
        val ids = ledger().list().filter { it.group == LearnedGroup.MESSAGES && it.sourcePackage == "com.slack" }.map { it.key }
        val r = ledger().forgetKeys(ids + "m:m-dinner" + "core:${LegacyVault.WHO_I_AM}")
        assertEquals(1, r.messages); assertEquals(1, r.memories); assertEquals(1, r.coreBlocks)
        val left = ledger().list()
        assertTrue(left.none { it.text.contains("ZebraFinch") || it.key == "m:m-dinner" || it.group == LearnedGroup.CORE })
        assertEquals("", memory.load().coreBlock(LegacyVault.WHO_I_AM)!!.value)
    }

    @Test
    fun deleteFromAnAppAndNeverLearnThereAgain() {
        val plan = ledger().planApp("com.whatsapp")
        assertEquals(2, plan.messageIds.size)
        assertEquals("2 typed messages", plan.describe())
        val r = ledger().forgetApp("com.whatsapp", neverAgain = true)
        assertEquals(2, r.messages)
        assertEquals(setOf("com.whatsapp"), apps)
        assertTrue(ledger().list().none { it.sourcePackage == "com.whatsapp" })
        assertEquals(2, twin.count())
    }

    @Test
    fun deleteADateRangeTakesMessagesAndMemoriesOnly() {
        // [t0+day, t0+4day): two messages and m-fish (approved t0+3day).
        val r = ledger().forgetRange(t0 + day, t0 + 4 * day)
        assertEquals(2, r.messages); assertEquals(1, r.memories)
        assertEquals(listOf("m-dinner"), memory.load().memories.map { it.id })
        assertEquals(2, journal.all().size)
        assertTrue(memory.load().coreBlock(LegacyVault.WHO_I_AM)!!.value.isNotEmpty())
        assertEquals(listOf(t0, t0 + 5 * day), ledger().list().filter { it.group == LearnedGroup.MESSAGES }.map { it.atMillis }.sorted())
    }

    @Test
    fun forgetAPhraseEverywhereAndNeverLearnItAgain() {
        val r = ledger().forgetPhrase("See you at the LAKE", neverAgain = true)
        assertEquals(3, r.messages)
        assertEquals(1, twin.count())
        // Style phrases are computed from what is left, so the phrase is gone from them.
        val stats = TwinStyleStats.Builder().apply { twin.forEach { add(it.text) } }.build()
        assertTrue((stats.topBigrams + stats.topTrigrams).none { it.first.contains("lake") })
        // The list stores a keyed MAC, never the phrase, and blocks it from now on.
        assertEquals(1, phrases.size)
        assertFalse(phrases.single().contains("lake"))
        assertTrue(NeverLearnPhrases.matches("ok, see you at the lake!", phrases, key))
        assertFalse(NeverLearnPhrases.matches("see you at the lakehouse", phrases, key))
    }

    @Test
    fun phraseItemDeletesMessagesHoldingIt() {
        val r = ledger().forgetKeys(listOf("phrase:the lake"))
        assertEquals(3, r.messages); assertEquals(1, r.memories)
        assertEquals(listOf("m-dinner"), memory.load().memories.map { it.id })
    }

    @Test
    fun forgetAPhraseAlsoTakesJournalAnswersRecordingsAndCoreBlocks() {
        val (audio, w) = journal.newRecording()
        w.use { it.write("fLaC-not-really-PloverVoice".toByteArray()) }
        val plover = journal.add(JournalKinds.ANSWER, JSONObject().put("prompt", "Where did you grow up?")
            .put("text", "By the PloverMarsh, near the river.").put("audio", JSONArray().put(JSONObject().put("file", audio).put("ms", 900))), t0 + 6 * day)
        val settings = journal.add(JournalKinds.SETTINGS, JSONObject().put("children", JSONArray().put("PloverMarsh")), t0 + 6 * day)
        memory.update { s0 ->
            val c = LegacyVault.candidates(journal.all(), s0).single { it.source.recordId == plover }
            val s = LegacyVault.approve(s0, c, t0 + 7 * day, id = "m-plover")
            LegacyVault.setCore(s, LegacyVault.ABOUT_MY_KIDS, "We camp at PloverMarsh every summer.", t0)
        }
        twin.add("heading to ploverMARSH now", "com.whatsapp", t0 + 7 * day)
        searcher(memory.load()).use { it.sync(memory.load()) }

        val plan = ledger().planPhrase("plovermarsh")
        assertEquals(1, plan.messageIds.size); assertEquals(setOf("m-plover"), plan.memoryIds)
        assertEquals(setOf(LegacyVault.ABOUT_MY_KIDS), plan.coreLabels); assertEquals(setOf(plover), plan.journalIds)
        assertEquals(1, plan.recordings)
        assertEquals("1 typed message, 1 approved memory, 1 core memory (cleared whole), 1 journal answer, 1 recording", plan.describe())

        val r = ledger().forgetPhrase("PloverMarsh", neverAgain = true)
        assertEquals(1, r.messages); assertEquals(1, r.memories); assertEquals(1, r.coreBlocks); assertEquals(1, r.journalRecords)
        assertTrue(journal.all().none { it.id == plover })
        assertTrue("settings are never deleted here", journal.all().any { it.id == settings })
        assertTrue(runCatching { journal.readRecording(audio) }.isFailure)
        assertEquals("", memory.load().coreBlock(LegacyVault.ABOUT_MY_KIDS)!!.value)
        assertTrue(vectors.load().entries.none { it.memoryId == "m-plover" })
        assertTrue(ledger().list().none { it.group != LearnedGroup.PHRASES && it.text.contains("PloverMarsh", ignoreCase = true) })
        assertTrue(NeverLearnPhrases.matches("PloverMarsh again", phrases, key))

        // Neither the export nor the dead-man release archive carries it.
        val out = ByteArrayOutputStream()
        val s = TwinExporter(twin, journal, memory).export(out, pass.copyOf(), t0, 100_000)
        assertEquals(0, s.recordings)
        val plain = unzip(out.toByteArray()) { PassphraseArchive.Reader(it.inputStream(), TwinExporter.normalize(pass)) }
        val dm = DeadManSwitch.create(DeadManSwitch.GUARDIANS, LocalDate.of(2026, 11, 19))
        val rel = ByteArrayOutputStream()
        TwinExporter(twin, journal, memory).exportRelease(rel, PassphraseArchive.dataKeyParams(dm.state.dataKey, dm.state.archiveId), t0)
        val released = unzip(rel.toByteArray()) { PassphraseArchive.Reader(it.inputStream(), dm.state.dataKey) }
        for (archive in listOf(plain, released)) {
            assertFalse(archive.contains("PloverMarsh, near") || archive.contains("camp at PloverMarsh") || archive.contains("m-plover"))
            assertFalse(archive.contains("PloverVoice") || archive.contains("ploverMARSH"))
        }
    }

    // ---- exports, archives and re-index ------------------------------------------------------

    private fun unzip(bytes: ByteArray, reader: (ByteArray) -> java.io.InputStream): String {
        val sb = StringBuilder()
        ZipInputStream(reader(bytes)).use { z -> while (true) { z.nextEntry ?: break; sb.append(String(z.readBytes(), Charsets.UTF_8)).append('\n') } }
        return sb.toString()
    }

    @Test
    fun exportAndDeadManArchiveHonourDeletes() {
        ledger().forgetKeys(listOf("m:m-fish", "core:${LegacyVault.WHO_I_AM}", "journal:$fishRecord"))
        ledger().forgetApp("com.slack", neverAgain = false)

        val out = ByteArrayOutputStream()
        val s = TwinExporter(twin, journal, memory).export(out, pass.copyOf(), t0, 100_000)
        assertEquals(3, s.messages); assertEquals(1, s.memories); assertEquals(0, s.coreBlocks); assertEquals(1, s.journalRecords)
        val plain = unzip(out.toByteArray()) { PassphraseArchive.Reader(it.inputStream(), TwinExporter.normalize(pass)) }
        for (gone in listOf("OspreyPatience", "m-fish", "CoreMagpie", "ZebraFinch")) assertFalse("export has $gone", plain.contains(gone))
        assertTrue(plain.contains("HeronSupper"))

        val r = DeadManSwitch.create(DeadManSwitch.GUARDIANS, LocalDate.of(2026, 11, 18))
        val rel = ByteArrayOutputStream()
        TwinExporter(twin, journal, memory).exportRelease(rel, PassphraseArchive.dataKeyParams(r.state.dataKey, r.state.archiveId), t0)
        TwinExporter.verifyRelease(rel.toByteArray().inputStream(), r.state.dataKey)
        val released = unzip(rel.toByteArray()) { PassphraseArchive.Reader(it.inputStream(), r.state.dataKey) }
        for (gone in listOf("OspreyPatience", "m-fish", "CoreMagpie", "ZebraFinch")) assertFalse("release has $gone", released.contains(gone))
    }

    @Test
    fun noResurrectionAfterReindexWithTheModel() {
        ledger().forgetKeys(listOf("m:m-fish"))
        // A fresh open + sync (what the memory screen does) with the model present.
        val state = memory.load()
        searcher(state).use { search ->
            search.sync(state)
            assertTrue(search.search("fishing lake grandpa", 5).none { it.memory.id == "m-fish" })
        }
        assertEquals(listOf("m-dinner"), vectors.load().entries.map { it.memoryId })
        // Re-running review finds nothing to re-approve, and a second sync changes nothing.
        assertTrue(LegacyVault.candidates(journal.all(), state).isEmpty())
        searcher(state).use { it.sync(state) }
        assertEquals(listOf("m-dinner"), vectors.load().entries.map { it.memoryId })
        assertTrue(ledger().list().none { it.key == "m:m-fish" })
    }

    @Test
    fun emptyPlansChangeNothing() {
        val before = vectors.load().entries.size
        val r = ledger().forget(ledger().planPhrase("   "))
        assertEquals(0, r.messages + r.memories + r.vectors)
        assertEquals(before, vectors.load().entries.size)
        assertEquals(4, twin.count())
    }

    /** A deterministic on-device stand-in for EmbeddingGemma: bag of hashed words. */
    private object HashEmbedder : TextEmbedder {
        override fun embed(prefixedText: String): FloatArray {
            val v = FloatArray(32)
            for (t in MemoryIndex.tokens(prefixedText.substringAfter("text: "))) if (t.length > 2) v[Math.floorMod(t.hashCode(), 32)] += 1f
            return v
        }
    }
}
