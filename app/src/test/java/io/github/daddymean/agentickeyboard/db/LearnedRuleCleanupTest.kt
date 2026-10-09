package io.github.daddymean.agentickeyboard.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.util.LearnedRuleCleanup
import io.github.daddymean.agentickeyboard.util.LocalSpelling
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** KEYBOARD-008: the one-time cleanup removes only harmful rules, keeps a backup, and can undo itself. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LearnedRuleCleanupTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: KeyboardRepository
    private val context = ApplicationProvider.getApplicationContext<Context>()
    // A separate file: the real app's own start-up cleanup may run in the background.
    private val prefs = context.getSharedPreferences("learned_rule_cleanup_test", Context.MODE_PRIVATE)
    private val spelling = LocalSpelling(File("src/main/res/raw/wordlist.txt").readLines().take(10_000))

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = KeyboardRepository(db)
        runBlocking {
            repository.insertCorrections(
                listOf(
                    LearnedCorrection(typo = "teh", correction = "the", count = 15),
                    LearnedCorrection(typo = "tomorow", correction = "tomorrow", count = 8),
                    LearnedCorrection(typo = "dont", correction = "don't", count = 2),
                    LearnedCorrection(typo = "your", correction = "youre", count = 3),
                    LearnedCorrection(typo = "there", correction = "their", count = 1),
                    LearnedCorrection(typo = "whos", correction = "whose", count = 1)
                )
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun rules() = runBlocking { repository.getAllCorrectionsOnce().associate { it.typo to it.correction } }

    @Test
    fun `cleanup removes harmful rules once and restore puts them back`() = runBlocking {
        assertEquals(2, LearnedRuleCleanup.runOnce(prefs, repository, spelling::isKnownWord))
        assertEquals(
            mapOf("teh" to "the", "tomorow" to "tomorrow", "dont" to "don't", "whos" to "whose"),
            rules()
        )
        assertEquals(2, LearnedRuleCleanup.backupCount(prefs))

        // A second run does nothing, even if a bad rule appears again.
        repository.insertCorrections(listOf(LearnedCorrection(typo = "were", correction = "where")))
        assertEquals(0, LearnedRuleCleanup.runOnce(prefs, repository, spelling::isKnownWord))
        assertEquals("where", rules()["were"])

        assertEquals(2, LearnedRuleCleanup.restore(prefs, repository))
        val restored = repository.getAllCorrectionsOnce().associateBy { it.typo }
        assertEquals("youre", restored["your"]?.correction)
        assertEquals(3, restored["your"]?.count)
        assertEquals("their", restored["there"]?.correction)
        assertEquals(0, LearnedRuleCleanup.backupCount(prefs))
        assertEquals(0, LearnedRuleCleanup.restore(prefs, repository))
    }

    @Test
    fun `restore does not overwrite a rule the user has since made for the same word`() = runBlocking {
        LearnedRuleCleanup.runOnce(prefs, repository, spelling::isKnownWord)
        repository.insertCorrections(listOf(LearnedCorrection(typo = "there", correction = "three")))
        assertEquals(1, LearnedRuleCleanup.restore(prefs, repository))
        assertEquals("three", rules()["there"])
        assertEquals("youre", rules()["your"])
    }
}
