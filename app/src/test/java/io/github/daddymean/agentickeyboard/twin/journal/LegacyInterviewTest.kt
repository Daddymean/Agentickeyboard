package io.github.daddymean.agentickeyboard.twin.journal

import io.github.daddymean.agentickeyboard.twin.memory.LegacyVault
import io.github.daddymean.agentickeyboard.twin.memory.VaultState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** KEYBOARD-034 rows 1–3: weekly sessions, the kids' question queue, and the question asset. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegacyInterviewTest {
    private val root: File = listOf(File("."), File("..")).first { File(it, "app/src/main/assets").isDirectory }
    private val assetJson = File(root, "app/src/main/assets/" + LegacyInterview.ASSET).readText()
    private val bank = QuestionBank.parse(assetJson)
    private val monday = LocalDate.of(2026, 10, 19)
    private var nextId = 1L

    private fun rec(kind: String, body: JSONObject, at: Long = 1_760_000_000_000 + nextId) = JournalRecord(nextId++, at, kind, body)
    private fun answer(promptId: String, child: String = "") =
        rec(JournalKinds.ANSWER, JSONObject().put("promptId", promptId).put("child", child).put("prompt", "q").put("text", "An answer about $promptId."))

    @Test
    fun assetIsOurOwnAndAttributed() {
        val doc = JSONObject(assetJson)
        assertTrue(doc.getString("attribution").contains("inspired by the themes of StoryCorps Great Questions"))
        assertTrue(doc.getString("attribution").contains("no StoryCorps question is reproduced"))
        assertTrue(bank.size >= 40)
        assertEquals(bank.size, bank.map { it.id }.toSet().size)
        assertTrue(bank.all { it.id.startsWith("LI-") && (it.text.endsWith("?") || it.text.endsWith(".")) })
        assertTrue(bank.map { it.category }.toSet().size >= 10)
    }

    @Test
    fun aWeekHasSixToEightFreshQuestionsAndStaysFixed() {
        val s = LegacyInterview.plan(bank, emptyList(), monday.plusDays(3))
        assertEquals(monday, s.week)
        assertTrue(s.promptIds.size in LegacyInterview.MIN..LegacyInterview.MAX)
        assertEquals(s.promptIds.size, s.promptIds.toSet().size)
        assertTrue(s.promptIds.count { id -> bank.single { it.id == id }.heavy } <= 1)
        assertEquals(s.promptIds.size, s.promptIds.map { id -> bank.single { it.id == id }.category }.toSet().size) // one per topic
        // Saved: answering doesn't reshuffle the week.
        val saved = rec(JournalKinds.EVENT, LegacyInterview.sessionEvent(s))
        val records = listOf(saved, answer(s.promptIds[0]))
        assertEquals(s, LegacyInterview.savedSession(records, monday))
        assertNull(LegacyInterview.savedSession(records, monday.plusWeeks(1)))
        // Next week: nothing answered or already offered comes back first.
        val next = LegacyInterview.plan(bank, records, monday.plusWeeks(1))
        assertTrue(next.promptIds.none { it in s.promptIds })
    }

    @Test
    fun kidsQuestionsComeFirstAndCanBeWithdrawnOrAnswered() {
        val records = ArrayList<JournalRecord>()
        records += rec(JournalKinds.EVENT, LegacyInterview.newKidQuestion(records, "Kid 3", "  What was your first car?  "))
        records += rec(JournalKinds.EVENT, LegacyInterview.newKidQuestion(records, "Kid 1", "Did you ever want to live abroad?"))
        assertThrows(IllegalArgumentException::class.java) { LegacyInterview.newKidQuestion(records, "Guardian 1", "Hi there?") }
        assertThrows(IllegalArgumentException::class.java) { LegacyInterview.newKidQuestion(records, "Kid 2", "x".repeat(501)) }
        val qs = LegacyInterview.kidQuestions(records)
        assertEquals(listOf("Kid 3", "Kid 1"), qs.map { it.kid })
        assertEquals("What was your first car?", qs[0].text)

        val s = LegacyInterview.plan(bank, records, monday)
        assertEquals(qs.map { it.promptId }, s.promptIds.take(2))
        assertEquals(LegacyInterview.MIN, s.promptIds.size)

        // Keith answers Kid 3's question: it's marked answered and becomes a memory candidate "for Kid 3".
        val p = LegacyInterview.prompt(qs[0])
        records += answer(p.id, child = "Kid 3")
        val after = LegacyInterview.kidQuestions(records)
        assertTrue(after[0].answered)
        assertFalse(after[1].answered)
        val candidates = LegacyVault.candidates(records, VaultState())
        assertTrue(candidates.any { it.child == "Kid 3" })
        // Kid questions themselves never become memories (events aren't learned).
        assertTrue(candidates.none { it.text.contains("first car") && it.child == null })

        // Withdrawing deletes the record, so it leaves the queue.
        records.removeAll { it.id == qs[1].recordId }
        assertEquals(1, LegacyInterview.kidQuestions(records).size)
    }

    @Test
    fun manyKidQuestionsCapTheSessionAtEight() {
        val records = ArrayList<JournalRecord>()
        for (i in 1..10) records += rec(JournalKinds.EVENT, LegacyInterview.newKidQuestion(records, "Kid ${1 + i % 6}", "Question number $i for Dad?"))
        val s = LegacyInterview.plan(bank, records, monday)
        assertEquals(LegacyInterview.MAX, s.promptIds.size)
        assertTrue(s.promptIds.all { it.startsWith(LegacyInterview.KID_PREFIX) })
    }
}
