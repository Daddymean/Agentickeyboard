package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.db.AppPersona
import io.github.daddymean.agentickeyboard.db.CustomCommand
import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import io.github.daddymean.agentickeyboard.db.WritingLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardPassportImportPlannerTest {

    private val current = KeyboardPassportSnapshot(
        personaPreference = "Casual",
        vocabulary = listOf(
            UserVocabulary("alpha", count = 2, lastUsed = 10),
            UserVocabulary("keep", count = 4, lastUsed = 20)
        ),
        corrections = listOf(LearnedCorrection(typo = "teh", correction = "the", count = 3)),
        shortcuts = listOf(ShortcutTemplate(shortcut = "omw", template = "On my way")),
        customCommands = listOf(CustomCommand(token = "/boss", instruction = "formal")),
        appPersonas = listOf(AppPersona("com.chat", "Casual", "Chat")),
        writingLogs = listOf(
            WritingLog(originalText = "Existing", sentiment = "Neutral", toneScore = 0.5f, wordCount = 1, timestamp = 5)
        )
    )

    @Test
    fun mergeCombinesCountsAndLetsIncomingKeyedRecordsWin() {
        val payload = PassportPayload(
            vocabulary = listOf(
                ImportedVocabulary("ALPHA", count = 5, lastUsed = 30),
                ImportedVocabulary("new", count = 1, lastUsed = 40)
            ),
            corrections = listOf(ImportedCorrection("teh", "tech", count = 2)),
            shortcuts = listOf(PassportShortcut("OMW", "Leaving now")),
            customCommands = listOf(PassportCustomCommand("boss", "warm and formal")),
            appPersonas = listOf(PassportAppPersona("com.chat", "Professional", "Chat Pro"))
        )
        val categories = setOf(
            PassportCategory.VOCABULARY,
            PassportCategory.CORRECTIONS,
            PassportCategory.SHORTCUTS,
            PassportCategory.CUSTOM_COMMANDS,
            PassportCategory.APP_PERSONAS
        )

        val plan = KeyboardPassportImportPlanner.plan(
            current = current,
            incoming = payload,
            categories = categories,
            mode = KeyboardPassportImportMode.MERGE
        )

        val alpha = plan.snapshot.vocabulary.first { it.word == "alpha" }
        assertEquals(7, alpha.count)
        assertEquals(30, alpha.lastUsed)
        assertTrue(plan.snapshot.vocabulary.any { it.word == "keep" })
        assertTrue(plan.snapshot.vocabulary.any { it.word == "new" })
        assertEquals("tech", plan.snapshot.corrections.single().correction)
        assertEquals(5, plan.snapshot.corrections.single().count)
        assertEquals("Leaving now", plan.snapshot.shortcuts.single().template)
        assertEquals("/boss", plan.snapshot.customCommands.single().token)
        assertEquals("warm and formal", plan.snapshot.customCommands.single().instruction)
        assertEquals("Professional", plan.snapshot.appPersonas.single().persona)
        assertEquals("Casual", plan.snapshot.personaPreference)
    }

    @Test
    fun replaceChangesOnlyCategoriesListedByPassport() {
        val payload = PassportPayload(
            vocabulary = listOf(ImportedVocabulary("replacement", 2, 99)),
            shortcuts = emptyList(),
            corrections = listOf(ImportedCorrection("dont", "don't", 1))
        )
        val categories = setOf(PassportCategory.VOCABULARY, PassportCategory.SHORTCUTS)

        val plan = KeyboardPassportImportPlanner.plan(
            current = current,
            incoming = payload,
            categories = categories,
            mode = KeyboardPassportImportMode.REPLACE
        )

        assertEquals(listOf("replacement"), plan.snapshot.vocabulary.map { it.word })
        assertTrue(plan.snapshot.shortcuts.isEmpty())
        assertEquals(current.corrections, plan.snapshot.corrections)
        assertEquals(current.customCommands, plan.snapshot.customCommands)
        assertEquals(current.appPersonas, plan.snapshot.appPersonas)
        assertEquals(current.writingLogs, plan.snapshot.writingLogs)
        assertEquals(current.personaPreference, plan.snapshot.personaPreference)
        assertFalse(PassportCategory.CORRECTIONS in plan.affectedCategories)
    }

    @Test
    fun emptyIncludedCategoryCanIntentionallyClearItOnReplace() {
        val plan = KeyboardPassportImportPlanner.plan(
            current = current,
            incoming = PassportPayload(shortcuts = emptyList()),
            categories = setOf(PassportCategory.SHORTCUTS),
            mode = KeyboardPassportImportMode.REPLACE
        )

        assertTrue(plan.snapshot.shortcuts.isEmpty())
        assertEquals(current.vocabulary, plan.snapshot.vocabulary)
    }

    @Test
    fun writingLogMergeDeduplicatesExactRecords() {
        val duplicate = ImportedLog("Existing", "Neutral", 0.5f, 5)
        val fresh = ImportedLog("Fresh note", "Joyful", 0.9f, 6)

        val plan = KeyboardPassportImportPlanner.plan(
            current = current,
            incoming = PassportPayload(writingLogs = listOf(duplicate, fresh)),
            categories = setOf(PassportCategory.WRITING_LOGS),
            mode = KeyboardPassportImportMode.MERGE
        )

        assertEquals(2, plan.snapshot.writingLogs.size)
        assertEquals(listOf("Existing", "Fresh note"), plan.snapshot.writingLogs.map { it.originalText })
    }

    @Test
    fun personaChangesOnlyWhenCategoryIsPresentAndValueIsUsable() {
        val absent = KeyboardPassportImportPlanner.plan(
            current,
            PassportPayload(personaPreference = "Professional"),
            emptySet(),
            KeyboardPassportImportMode.REPLACE
        )
        assertEquals("Casual", absent.snapshot.personaPreference)

        val present = KeyboardPassportImportPlanner.plan(
            current,
            PassportPayload(personaPreference = " Professional "),
            setOf(PassportCategory.PERSONA_PREFERENCE),
            KeyboardPassportImportMode.MERGE
        )
        assertEquals("Professional", present.snapshot.personaPreference)
    }

    @Test
    fun malformedRecordsAreDroppedAndCountsCannotOverflow() {
        val plan = KeyboardPassportImportPlanner.plan(
            current = current.copy(
                vocabulary = listOf(UserVocabulary("alpha", Int.MAX_VALUE, 1)),
                corrections = emptyList(),
                customCommands = emptyList()
            ),
            incoming = PassportPayload(
                vocabulary = listOf(ImportedVocabulary("alpha", 10, 2), ImportedVocabulary(" ", 3, 1)),
                corrections = listOf(ImportedCorrection("", "x", 1)),
                customCommands = listOf(PassportCustomCommand("bad token", "instruction"))
            ),
            categories = setOf(
                PassportCategory.VOCABULARY,
                PassportCategory.CORRECTIONS,
                PassportCategory.CUSTOM_COMMANDS
            ),
            mode = KeyboardPassportImportMode.MERGE
        )

        assertEquals(Int.MAX_VALUE, plan.snapshot.vocabulary.single().count)
        assertTrue(plan.snapshot.corrections.isEmpty())
        assertTrue(plan.snapshot.customCommands.isEmpty())
    }
}