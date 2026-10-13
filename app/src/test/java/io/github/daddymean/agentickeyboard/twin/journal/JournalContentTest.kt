package io.github.daddymean.agentickeyboard.twin.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JournalContentTest {
    private val bank = QuestionBank.parse(JournalAssets.asset("question-bank.json"))

    @Test
    fun questionBankLoadsEveryPrompt() {
        assertEquals(283, bank.size)
        assertEquals(bank.size, bank.map { it.id }.toSet().size)
        assertEquals(21, bank.map { it.category }.toSet().size)
        assertTrue(bank.all { it.text.isNotBlank() && it.depth in 1..3 })
        assertTrue(bank.any { it.perChild })
        assertTrue(bank.any { it.revisit == Revisit.YEARLY } && bank.any { it.revisit == Revisit.MILESTONE_AND_YEARLY })
        assertTrue(bank.filter { it.emotionallyHeavy }.all { it.heavy })
    }

    @Test
    fun placeholdersAreFilled() {
        val p = bank.first { it.text.contains("[child name]") }
        val filled = JournalScheduler.fill(p, "Ava", null)
        assertTrue(filled.contains("Ava") && !filled.contains("[child name]"))
        val noPlaceholder = bank.first { it.perChild && !it.text.contains("[child name]") }
        assertTrue(JournalScheduler.fill(noPlaceholder, "Ava", null).startsWith("For Ava: "))
        bank.firstOrNull { it.text.contains("[partner name]") }?.let {
            assertTrue(JournalScheduler.fill(it, null, null).contains("your partner"))
            assertTrue(JournalScheduler.fill(it, null, "Sam").contains("Sam"))
        }
    }

    @Test
    fun voiceScriptsHaveEightSessions() {
        val md = JournalAssets.asset("voice-scripts.md")
        val sessions = VoiceScripts.parse(md)
        assertEquals((1..8).toList(), sessions.map { it.number })
        assertTrue(sessions.all { it.text.length > 200 })
        assertTrue(sessions.none { it.text.contains("**") || it.text.contains("## ") })
        assertTrue(VoiceScripts.setup(md).isNotBlank())
        assertTrue(sessions.none { it.text.contains("Sources and licences") })
    }

    @Test
    fun settingsRoundTrip() {
        val s = JournalSettings(listOf("Ava", "Ben"), "Sam", true, java.time.LocalDate.of(2026, 11, 1), java.time.LocalDate.of(2026, 10, 1))
        assertEquals(s, JournalSettings.fromJson(s.toJson()))
    }
}
