package io.github.daddymean.agentickeyboard.learned

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.AppPersona
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import io.github.daddymean.agentickeyboard.db.WritingLog
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.LearnedRuleCleanup
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** KEYBOARD-047: each keyboard store's delete-one and clear paths, clear-all, and secure_delete. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardLearnedDataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: KeyboardRepository
    private lateinit var settings: KeyboardSettings
    private val cleanup = context.getSharedPreferences("learned_rule_cleanup_kld_test", Context.MODE_PRIVATE)
    private lateinit var data: KeyboardLearnedData

    @Before
    fun setUp() = runBlocking {
        context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).edit().clear().commit()
        cleanup.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.SECURE_DELETE).allowMainThreadQueries().build()
        repo = KeyboardRepository(db)
        settings = KeyboardSettings(context)
        data = KeyboardLearnedData(db, settings, cleanup)

        repo.insertWords(listOf(UserVocabulary("hello", 5, 1_000), UserVocabulary("world", 3, 2_000), UserVocabulary("keith", 9, 3_000)))
        repo.recordBigram("hello", "world"); repo.recordBigram("hello", "keith"); repo.recordBigram("good", "morning")
        repo.insertCorrections(listOf(LearnedCorrection(typo = "teh", correction = "the", count = 4), LearnedCorrection(typo = "adn", correction = "and")))
        repo.insertLogs(listOf(WritingLog(originalText = "secret plan one", sentiment = "Calm", toneScore = .5f, wordCount = 3, timestamp = 10),
            WritingLog(originalText = "second note", sentiment = "Calm", toneScore = .5f, wordCount = 2, timestamp = 20)))
        repo.upsertAppPersonas(listOf(AppPersona("com.slack", "Professional", "Slack"), AppPersona("com.whatsapp", "Casual", "WhatsApp")))
        repo.captureClipboard("clip a", "h1", seenAt = System.currentTimeMillis())
        repo.captureClipboard("clip b", "h2", seenAt = System.currentTimeMillis())
        cleanup.edit().putString("v1_backup", """[{"typo":"there","correction":"their","count":1},{"typo":"your","correction":"youre","count":2}]""").commit()
        settings.autoFixNeverWords = setOf("gonna", "y'all")
        settings.touchCalibration = "a,0.1,0.0,5;s,0.0,0.1,4"
    }

    @After
    fun tearDown() { db.close() }

    private fun one(store: KeyboardStore, text: String) = runBlocking { data.entries(store, 100).first { it.text == text } }

    @Test
    fun countsCoverEveryStore() = runBlocking {
        val c = data.counts()
        assertEquals(KeyboardStore.values().toSet(), c.keys)
        assertEquals(mapOf(
            KeyboardStore.WORDS to 3, KeyboardStore.WORD_PAIRS to 3, KeyboardStore.CORRECTIONS to 2, KeyboardStore.REMOVED_RULES to 2,
            KeyboardStore.NEVER_AUTOFIX to 2, KeyboardStore.WRITING_LOG to 2, KeyboardStore.APP_STYLES to 2, KeyboardStore.CLIPBOARD to 2,
            KeyboardStore.TOUCH_CALIBRATION to 2), c)
    }

    @Test
    fun deletingAWordAlsoDropsItsNextWordPairs() = runBlocking {
        assertEquals(3, data.delete(one(KeyboardStore.WORDS, "hello"))) // 1 word + 2 pairs
        assertEquals(null, repo.getWord("hello"))
        assertEquals(emptyList<String>(), repo.nextWords("hello"))
        assertEquals(listOf("morning"), repo.nextWords("good"))
        // 2 words + the 1 pair left ("good → morning"): clearing words leaves no pairs (#231 review blocker 2).
        assertEquals(3, data.clear(KeyboardStore.WORDS))
        assertEquals(0, data.count(KeyboardStore.WORDS))
        assertEquals(0, data.count(KeyboardStore.WORD_PAIRS))
        assertEquals(emptyList<String>(), repo.nextWords("good"))
    }

    @Test
    fun clearingWordsAloneLeavesNoPairs() = runBlocking {
        assertEquals(6, data.clear(KeyboardStore.WORDS)) // 3 words + 3 pairs
        assertEquals(0, db.wordBigramDao().count())
        assertEquals(emptyList<String>(), repo.nextWords("hello"))
        assertEquals(emptyList<String>(), repo.nextWords("good"))
    }

    @Test
    fun wordPairsDeleteOneAndClear() = runBlocking {
        assertEquals(1, data.delete(one(KeyboardStore.WORD_PAIRS, "hello → keith")))
        assertEquals(listOf("world"), repo.nextWords("hello"))
        assertEquals(2, data.clear(KeyboardStore.WORD_PAIRS))
        assertEquals(0, data.count(KeyboardStore.WORD_PAIRS))
    }

    @Test
    fun correctionsDeleteOneAndClear() = runBlocking {
        assertEquals(1, data.delete(one(KeyboardStore.CORRECTIONS, "teh → the")))
        assertEquals(null, repo.getCorrectionForTypo("teh"))
        assertEquals(1, data.clear(KeyboardStore.CORRECTIONS))
        assertEquals(0, data.count(KeyboardStore.CORRECTIONS))
    }

    @Test
    fun removedRuleBackupDeleteOneAndClear() = runBlocking {
        assertEquals(1, data.delete(one(KeyboardStore.REMOVED_RULES, "there → their")))
        assertEquals(listOf("your"), LearnedRuleCleanup.backupEntries(cleanup).map { it.typo })
        assertEquals(1, data.clear(KeyboardStore.REMOVED_RULES))
        assertEquals(0, LearnedRuleCleanup.backupCount(cleanup))
        // Restore has nothing left to bring back.
        assertEquals(0, LearnedRuleCleanup.restore(cleanup, repo))
    }

    @Test
    fun neverAutoFixDeleteOneAndClear() = runBlocking {
        assertEquals(1, data.delete(one(KeyboardStore.NEVER_AUTOFIX, "gonna")))
        assertEquals(setOf("y'all"), settings.autoFixNeverWords)
        assertEquals(1, data.clear(KeyboardStore.NEVER_AUTOFIX))
        assertTrue(settings.autoFixNeverWords.isEmpty())
    }

    @Test
    fun writingLogDeleteOneAndClear() = runBlocking {
        assertEquals(1, data.delete(one(KeyboardStore.WRITING_LOG, "secret plan one")))
        assertEquals(listOf("second note"), data.entries(KeyboardStore.WRITING_LOG, 10).map { it.text })
        assertEquals(1, data.clear(KeyboardStore.WRITING_LOG))
        assertEquals(0, data.count(KeyboardStore.WRITING_LOG))
    }

    @Test
    fun appStylesDeleteOneAndClear() = runBlocking {
        assertEquals(1, data.delete(one(KeyboardStore.APP_STYLES, "Slack: Professional")))
        assertEquals(null, repo.getAppPersona("com.slack"))
        assertEquals(1, data.clear(KeyboardStore.APP_STYLES))
        assertEquals(null, repo.getAppPersona("com.whatsapp"))
    }

    @Test
    fun clipboardDeleteOneAndClearIncludingPinned() = runBlocking {
        assertEquals(1, data.delete(one(KeyboardStore.CLIPBOARD, "clip a")))
        val b = db.clipboardHistoryDao().getAllNow().single()
        assertTrue(repo.setClipboardPinned(b.id, true))
        assertEquals(1, data.clear(KeyboardStore.CLIPBOARD))
        assertEquals(0, data.count(KeyboardStore.CLIPBOARD))
    }

    @Test
    fun touchCalibrationDeleteOneLetterAndClear() = runBlocking {
        assertEquals(1, data.delete(one(KeyboardStore.TOUCH_CALIBRATION, "Key A")))
        assertEquals(listOf("Key S"), data.entries(KeyboardStore.TOUCH_CALIBRATION, 10).map { it.text })
        assertEquals(1, data.clear(KeyboardStore.TOUCH_CALIBRATION))
        assertEquals("", settings.touchCalibration)
    }

    @Test
    fun clearAllEmptiesEveryStoreAndIsIdempotent() = runBlocking {
        val gone = data.clearAll()
        assertEquals(20, gone.values.sum())
        assertTrue(data.counts().values.all { it == 0 })
        assertEquals(0, db.wordBigramDao().count())
        assertEquals(0, db.userVocabularyDao().count())
        assertEquals(0, data.clearAll().values.sum())
        // Deleting an entry that's already gone is a no-op, not an error.
        assertEquals(0, data.delete(KeyboardEntry(KeyboardStore.CLIPBOARD, "999", "", "")))
        assertEquals(0, data.delete(KeyboardEntry(KeyboardStore.WORD_PAIRS, "bad-key", "", "")))
    }

    @Test
    fun deletedTextIsGoneFromTheDatabaseAndSecureDeleteIsOn() = runBlocking {
        assertTrue(AppDatabase.enableSecureDelete(db.openHelper.writableDatabase))
        data.clear(KeyboardStore.WRITING_LOG)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM writing_logs WHERE originalText LIKE '%secret%'").use {
            it.moveToFirst(); assertEquals(0, it.getInt(0))
        }
        // The WAL checkpoint runs without error (in-memory has no WAL, so it reports done).
        assertTrue(data.scrubWal())
        assertFalse(data.entries(KeyboardStore.WRITING_LOG, 10).any { it.text.contains("secret") })
    }

    @Test
    fun clearingWordsReportsWordsAndPairsApart() = runBlocking {
        // #231 nit 4: the toast counts words as words, not words plus pairs.
        val gone = data.clearCounted(KeyboardStore.WORDS)
        assertEquals(mapOf(KeyboardStore.WORDS to 3, KeyboardStore.WORD_PAIRS to 3), gone)
        assertEquals("Deleted learned words: 3, next-word pairs: 3.", deletedMessage(gone))
        assertEquals("Nothing was left to delete.", deletedMessage(data.clearCounted(KeyboardStore.WORDS)))
    }

    @Test
    fun deletingOneWordReportsItsPairsApart() = runBlocking {
        val gone = data.deleteCounted(one(KeyboardStore.WORDS, "hello"))
        assertEquals(mapOf(KeyboardStore.WORDS to 1, KeyboardStore.WORD_PAIRS to 2), gone)
        assertEquals("Deleted learned words: 1, next-word pairs: 2.", deletedMessage(gone))
    }

    @Test
    fun clearAllCountsPairsUnderPairs() = runBlocking {
        val gone = data.clearAll()
        assertEquals(3, gone[KeyboardStore.WORDS])
        assertEquals(3, gone[KeyboardStore.WORD_PAIRS])
    }

    @Test
    fun learningCorrectionsBumpsOrAddsInOneTransaction() = runBlocking {
        // #231 review 2, note 2: proofread learning reads and writes in one transaction.
        repo.learnCorrections(listOf("teh" to "the", "recieve" to "receive"))
        assertEquals(5, repo.getCorrectionForTypo("teh")?.count)
        assertEquals("receive", repo.getCorrectionForTypo("recieve")?.correction)
        assertEquals(3, data.count(KeyboardStore.CORRECTIONS))
        // A rule deleted before learning runs is added fresh, never with its old count.
        data.delete(one(KeyboardStore.CORRECTIONS, "teh → the"))
        repo.learnCorrections(listOf("teh" to "the"))
        assertEquals(1, repo.getCorrectionForTypo("teh")?.count)
    }
}

