package io.github.daddymean.agentickeyboard.twin.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import io.github.daddymean.agentickeyboard.twin.TwinStore
import io.github.daddymean.agentickeyboard.twin.TwinVault
import io.github.daddymean.agentickeyboard.twin.journal.JournalKinds
import io.github.daddymean.agentickeyboard.twin.journal.JournalRecord
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore
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
import java.io.File

/** KEYBOARD-030 rows 1–4: allowed fields, the approval gate, core blocks, sealed at rest, cascades. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyVaultTest {
    private lateinit var context: Context
    private lateinit var dir: File

    @Before fun setUp() { context = ApplicationProvider.getApplicationContext(); dir = File(context.noBackupFilesDir, "twin-memory-test").also { it.deleteRecursively() } }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun fails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    private fun answer(id: Long, text: String, transcript: String = "", child: String? = null) = JournalRecord(id, 1_760_000_000_000 + id, JournalKinds.ANSWER,
        JSONObject().put("kind", JournalKinds.ANSWER).put("promptId", "p$id").put("prompt", "What do you want the kids to know about work?")
            .put("category", "work").put("text", text).put("transcript", transcript).put("audio", JSONArray()).also { o -> child?.let { o.put("child", it) } })

    private val records = listOf(
        answer(1, "Work hard, but come home for dinner. Call me on 555 123 4567.", transcript = "I always tried to be home by six."),
        JournalRecord(2, 1_760_000_000_002, JournalKinds.INSTRUMENT, JSONObject().put("kind", "instrument").put("instrumentId", "bfi").put("text", "instrument free text")),
        JournalRecord(3, 1_760_000_000_003, JournalKinds.SETTINGS, JSONObject().put("kind", "settings").put("children", JSONArray(listOf("Ava"))).put("text", "settings text")),
        JournalRecord(4, 1_760_000_000_004, JournalKinds.EVENT, JSONObject().put("kind", "event").put("text", "event text")),
        JournalRecord(5, 1_760_000_000_005, JournalKinds.CARD_SORT, JSONObject().put("kind", "card_sort").put("top5", JSONArray(listOf("Family", "Honesty", "Curiosity")))),
        answer(6, "password: hunter2hunter2", child = "Kid 1")
    )

    @Test
    fun onlyAllowedFieldsBecomeScrubbedCandidates() {
        val c = LegacyVault.candidates(records, VaultState())
        assertEquals(listOf("journal:1:text", "journal:1:transcript", "journal:5:top5"), c.map { it.source.key })
        assertFalse(c.any { "instrument" in it.text || "settings" in it.text || "event" in it.text })
        val work = c.first { it.source.key == "journal:1:text" }
        assertFalse("phone number scrubbed", work.text.contains("4567"))
        assertTrue(work.text.startsWith("Work hard, but come home for dinner."))
        assertEquals("My top values: Family, Honesty, Curiosity.", c.first { it.source.field == "top5" }.text)
        assertEquals("What do you want the kids to know about work?", work.prompt)
    }

    @Test
    fun nothingEntersWithoutApprovalAndDecisionsStick() {
        var s = VaultState()
        val c = LegacyVault.candidates(records, s)
        assertTrue(s.memories.isEmpty())
        s = LegacyVault.approve(s, c[0], now = 10, id = "m1")
        s = LegacyVault.reject(s, c[1])
        assertEquals(listOf("m1"), s.memories.map { it.id })
        assertEquals(listOf("journal:5:top5"), LegacyVault.candidates(records, s).map { it.source.key })
        fails { LegacyVault.approve(s, c[0], 11) }
        fails { LegacyVault.approve(s, c[1], 11) }
        // Edits are scrubbed; an edit that scrubs to nothing is refused.
        fails { LegacyVault.approve(s, c[2], 12, editedText = "My PIN is 99887766.") } // credential-like: dropped whole
        s = LegacyVault.approve(s, c[2], 12, editedText = "Family first. Call 555 123 4567.", id = "m2")
        assertTrue(s.memory("m2")!!.edited)
        assertFalse(s.memory("m2")!!.text.contains("4567"))
        assertEquals(c[2].text, s.memory("m2")!!.sourceText)
        // Removing a memory keeps its source out of review.
        s = LegacyVault.remove(s, "m1")
        assertTrue(s.memory("m1") == null)
        assertTrue(LegacyVault.candidates(records, s).isEmpty())
        // Deleting the journal record forgets everything derived from it.
        s = LegacyVault.forgetRecord(s, 1)
        assertFalse(s.rejected.any { it.startsWith("journal:1:") })
        assertEquals(listOf("m2"), s.memories.map { it.id })
    }

    @Test
    fun coreBlocksAreFixedLabelledAndCapped() {
        var s = VaultState()
        assertEquals(listOf(LegacyVault.WHO_I_AM, LegacyVault.ABOUT_MY_KIDS), s.core.map { it.label })
        s = LegacyVault.setCore(s, LegacyVault.WHO_I_AM, "  I'm Keith. I fix things and tell bad jokes.  ", 5)
        assertEquals("I'm Keith. I fix things and tell bad jokes.", s.coreBlock(LegacyVault.WHO_I_AM)!!.value)
        assertEquals("", s.coreBlock(LegacyVault.ABOUT_MY_KIDS)!!.value)
        fails { LegacyVault.setCore(s, LegacyVault.ABOUT_MY_KIDS, "x".repeat(LegacyVault.CORE_LIMIT + 1), 6) }
        fails { LegacyVault.setCore(s, "secrets", "x", 6) }
        assertEquals(s, LegacyVault.fromJson(JSONObject(LegacyVault.toJson(s).toString())))
    }

    @Test
    fun vaultIsSealedAtRestAndCascades() {
        val cipher = SoftwareTwinCipher()
        val file = File(dir, "memory.sealed")
        val mem = MemoryStore(file, cipher)
        assertEquals(VaultState(), mem.load())
        val journal = JournalStore(context, dir, cipher)
        val id = journal.add(JournalKinds.ANSWER, answer(0, "ZebraMarmalade is our family word.").body, 1_760_000_000_000)
        val c = LegacyVault.candidates(journal.all(), mem.load()).single { it.source.field == "text" }
        mem.update { LegacyVault.setCore(LegacyVault.approve(it, c, 1, id = "m1"), LegacyVault.WHO_I_AM, "CoreSecretWord", 1) }
        val bytes = file.readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("ZebraMarmalade"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("CoreSecretWord"))
        assertEquals("m1", mem.load().memories.single().id)
        // Deleting the journal answer deletes its memory too.
        assertTrue(TwinVault.deleteJournalRecord(journal, mem, id))
        assertTrue(mem.load().memories.isEmpty())
        // Delete-all removes the vault file and destroys the key.
        val twin = TwinStore(context, File(dir, "twin_store.db"), cipher)
        val consentFile = File(dir, "consent.sealed")
        val consent = io.github.daddymean.agentickeyboard.twin.consent.ConsentStore(consentFile, cipher)
        consent.update { io.github.daddymean.agentickeyboard.twin.consent.TwinConsent.retire(it, "Kid 1", 1) }
        TwinVault.deleteAll(journal, twin, null, mem, consent)
        assertFalse(file.exists())
        assertFalse(consentFile.exists()) // KEYBOARD-033: the consent record goes too
        assertTrue(cipher.destroyed > 0)
        journal.close()
    }
}
