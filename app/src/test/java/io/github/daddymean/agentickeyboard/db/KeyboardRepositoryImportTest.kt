package io.github.daddymean.agentickeyboard.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.util.KeyboardPassport
import io.github.daddymean.agentickeyboard.util.KeyboardPassportImportMode
import io.github.daddymean.agentickeyboard.util.KeyboardPassportOpenResult
import io.github.daddymean.agentickeyboard.util.KeyboardPassportOptions
import io.github.daddymean.agentickeyboard.util.KeyboardPassportTransfer
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.PassportCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Batched import writes must leave the database exactly as the former
 * record-at-a-time loops did.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardRepositoryImportTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: KeyboardRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = KeyboardRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun personalModelImportAddsCountsAndFoldsRepeatedWords() = runBlocking {
        repository.insertWord(UserVocabulary(word = "hello", count = 3, lastUsed = 500L))

        repository.importPersonalModel(
            vocabulary = listOf(
                UserVocabulary(word = "hello", count = 2, lastUsed = 100L),
                UserVocabulary(word = "world", count = 1, lastUsed = 0L),
                UserVocabulary(word = "hello", count = 4, lastUsed = 900L)
            ),
            corrections = emptyList(),
            logs = emptyList()
        )

        assertEquals(UserVocabulary(word = "hello", count = 9, lastUsed = 900L), repository.getWord("hello"))
        // lastUsed is floored at 1 so imported words never look "never used".
        assertEquals(UserVocabulary(word = "world", count = 1, lastUsed = 1L), repository.getWord("world"))
    }

    @Test
    fun personalModelImportUpdatesStoredCorrectionAndLastRepeatWins() = runBlocking {
        repository.insertCorrection(LearnedCorrection(typo = "teh", correction = "tea", count = 5))
        val storedId = repository.getCorrectionForTypo("teh")!!.id

        repository.importPersonalModel(
            vocabulary = emptyList(),
            corrections = listOf(
                LearnedCorrection(typo = "teh", correction = "ten", count = 1),
                LearnedCorrection(typo = "recieve", correction = "recive", count = 2),
                LearnedCorrection(typo = "teh", correction = "the", count = 2),
                LearnedCorrection(typo = "recieve", correction = "receive", count = 3)
            ),
            logs = emptyList()
        )

        val rows = repository.allCorrections.first().associateBy { it.typo }
        assertEquals(2, rows.size)
        assertEquals(LearnedCorrection(id = storedId, typo = "teh", correction = "the", count = 8), rows["teh"])
        assertEquals("receive", rows.getValue("recieve").correction)
        assertEquals(5, rows.getValue("recieve").count)
    }

    @Test
    fun personalModelImportAppendsLogs() = runBlocking {
        repository.insertLog(WritingLog(originalText = "existing", sentiment = "", toneScore = 0f, wordCount = 1, timestamp = 1L))

        repository.importPersonalModel(
            vocabulary = emptyList(),
            corrections = emptyList(),
            logs = (1..3).map {
                WritingLog(originalText = "log $it", sentiment = "", toneScore = 0f, wordCount = 2, timestamp = 10L + it)
            }
        )

        assertEquals(
            listOf("log 3", "log 2", "log 1", "existing"),
            repository.allLogs.first().map { it.originalText }
        )
    }

    @Test
    fun batchDeletesHandleMoreRowsThanOneStatementCanBind() = runBlocking {
        repository.insertCustomCommands((1..2_000).map { CustomCommand(token = "/c$it", instruction = "i$it") })
        repository.insertShortcuts((1..2_000).map { ShortcutTemplate(shortcut = "s$it", template = "t$it") })
        repository.upsertAppPersonas((1..2_000).map { AppPersona(packageName = "com.app$it", persona = "Professional") })

        repository.deleteCustomCommandsByIds(repository.allCustomCommands.first().map { it.id })
        repository.deleteShortcutsByIds(repository.allShortcuts.first().map { it.id })
        repository.deleteAppPersonas(repository.allAppPersonas.first().map { it.packageName })

        assertTrue(repository.allCustomCommands.first().isEmpty())
        assertTrue(repository.allShortcuts.first().isEmpty())
        assertTrue(repository.allAppPersonas.first().isEmpty())
    }

    @Test
    fun passportReplaceRestoresEveryCategoryExactly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val transfer = KeyboardPassportTransfer(db, repository, KeyboardSettings(context))

        repository.insertWords(listOf(UserVocabulary("alpha", 2, 10L), UserVocabulary("beta", 1, 20L)))
        repository.insertCorrections(listOf(LearnedCorrection(typo = "teh", correction = "the", count = 3)))
        repository.insertShortcuts(listOf(ShortcutTemplate(shortcut = "brb", template = "be right back")))
        repository.insertCustomCommands(listOf(CustomCommand(token = "/pirate", instruction = "talk like a pirate")))
        repository.upsertAppPersonas(listOf(AppPersona(packageName = "com.slack", persona = "Professional", appLabel = "Slack")))
        repository.insertLogs(listOf(WritingLog(originalText = "see you soon", sentiment = "Joyful", toneScore = 0.5f, wordCount = 3, timestamp = 30L)))

        val passport = transfer.createPassport(
            KeyboardPassportOptions(includedCategories = PassportCategory.entries.toSet(), redactSensitiveText = false)
        )
        val expected = contents()

        // Drift away from the exported state in every category.
        repository.insertWord(UserVocabulary("gamma", 7, 40L))
        repository.insertShortcuts(listOf(ShortcutTemplate(shortcut = "omw", template = "on my way")))
        repository.insertCustomCommands(listOf(CustomCommand(token = "/shout", instruction = "all caps")))
        repository.upsertAppPersonas(listOf(AppPersona(packageName = "com.whatsapp", persona = "Joyful")))
        repository.insertLogs(listOf(WritingLog(originalText = "extra", sentiment = "", toneScore = 0f, wordCount = 1, timestamp = 50L)))
        repository.deleteCustomCommandsByIds(listOf(repository.allCustomCommands.first().first { it.token == "/pirate" }.id))

        val opened = KeyboardPassport.open(passport) as KeyboardPassportOpenResult.Success
        transfer.apply(opened, KeyboardPassportImportMode.REPLACE)

        assertEquals(expected, contents())
    }

    /** Table contents without autogenerated ids, which a replace reassigns. */
    private suspend fun contents(): List<Any> = listOf(
        db.openHelper.readableDatabase.query("SELECT word, count, lastUsed FROM user_vocabulary ORDER BY word").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getInt(1), c.getLong(2))) }
        },
        repository.allCorrections.first().map { Triple(it.typo, it.correction, it.count) },
        repository.allShortcuts.first().map { it.shortcut to it.template },
        repository.allCustomCommands.first().map { it.token to it.instruction },
        repository.allAppPersonas.first(),
        repository.allLogs.first().map { listOf(it.originalText, it.sentiment, it.toneScore, it.wordCount, it.timestamp) }
    )
}
