package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.network.GeminiManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Keith, 2026-11-25 5:26 PM PT ("1. Yes"): pausing learning also stops grammar
 * fix and tone check from saving to the writing log. The checks themselves
 * still run; only the log row is skipped. Writes resume after learning is
 * unpaused. Password, incognito, sensitive and never-learn (twin privacy-
 * blocked) fields already refuse the checks, and must still leave the log empty.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WritingLogPauseTest {

    private val textType = InputType.TYPE_CLASS_TEXT
    private lateinit var db: AppDatabase
    private lateinit var vm: KeyboardViewModel
    private val previousOnDeviceAi = GeminiManager.onDeviceAi

    @Before
    fun setUp() {
        GeminiManager.onDeviceAi = null
        GeminiManager.privacyBlocked = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vm = KeyboardViewModel(KeyboardRepository(db))
        vm.toggleOfflineMode()
        assertTrue(vm.isOfflineMode.value)
        vm.onEditorStarted("com.example.chat", "Chat", textType)
        assertFalse(vm.isSensitiveField.value)
        assertFalse(vm.isLearningPaused.value)
    }

    @After
    fun tearDown() {
        shadowOf(Looper.getMainLooper()).idle()
        GeminiManager.onDeviceAi = previousOnDeviceAi
        GeminiManager.privacyBlocked = false
        db.close()
    }

    @Test
    fun `grammar fix and tone check write log rows when learning is not paused`() {
        vm.fixGrammar("I recieve the note")
        awaitPanel { it is AiPanelState.Grammar }
        assertEquals(setOf("I recieve the note"), awaitLogs(1))

        vm.analyzeTone("thanks, this is great")
        awaitPanel { it is AiPanelState.Tone }
        assertEquals(
            setOf("I recieve the note", "thanks, this is great"),
            awaitLogs(2)
        )
    }

    @Test
    fun `paused learning writes no grammar or tone rows, then writes resume after`() {
        vm.setLearningPaused(true)

        vm.fixGrammar("I recieve the note")
        awaitPanel { it is AiPanelState.Grammar }
        awaitNoNewLogs()

        vm.analyzeTone("thanks, this is great")
        awaitPanel { it is AiPanelState.Tone }
        awaitNoNewLogs()

        vm.setLearningPaused(false)
        vm.fixGrammar("I recieve another note")
        awaitPanel { state ->
            state is AiPanelState.Grammar && state.result.original == "I recieve another note"
        }
        assertEquals(setOf("I recieve another note"), awaitLogs(1))

        vm.analyzeTone("I am happy to help")
        awaitPanel { it is AiPanelState.Tone }
        assertEquals(
            setOf("I recieve another note", "I am happy to help"),
            awaitLogs(2)
        )
    }

    @Test
    fun `password incognito and never-learn fields write no log rows`() {
        vm.onEditorStarted(
            "com.example.bank",
            "Bank",
            textType or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        assertTrue(vm.isSensitiveField.value)
        vm.fixGrammar("I recieve the note")
        vm.analyzeTone("thanks, this is great")
        assertStayedIdleWithNoLogs()

        vm.onEditorStarted(
            "com.example.chat",
            "Chat",
            textType,
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        )
        assertTrue(vm.isSensitiveField.value)
        vm.fixGrammar("I recieve the note")
        vm.analyzeTone("thanks, this is great")
        assertStayedIdleWithNoLogs()

        vm.onEditorStarted("com.example.chat", "Chat", textType)
        assertFalse(vm.isSensitiveField.value)
        vm.applyTwinPrivacyBlock(true)
        assertTrue(vm.isSensitiveField.value)
        vm.fixGrammar("I recieve the note")
        vm.analyzeTone("thanks, this is great")
        assertStayedIdleWithNoLogs()
    }

    private fun logTexts(): Set<String> = vm.logs.value.map { it.originalText }.toSet()

    private fun awaitPanel(predicate: (AiPanelState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!predicate(vm.aiPanelState.value)) {
            check(System.currentTimeMillis() < deadline) {
                "AI panel never reached the expected state: ${vm.aiPanelState.value}"
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        // Room's eager logs flow may land just after the panel state.
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun awaitLogs(expected: Int): Set<String> {
        val deadline = System.currentTimeMillis() + 5_000
        while (logTexts().size != expected) {
            check(System.currentTimeMillis() < deadline) {
                "writing log stayed at ${logTexts()} instead of $expected rows"
            }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        return logTexts()
    }


    private fun awaitNoNewLogs(already: Int = 0) {
        val deadline = System.currentTimeMillis() + 500
        do {
            shadowOf(Looper.getMainLooper()).idle()
            check(logTexts().size == already) { "unexpected writing log rows: ${logTexts()}" }
            Thread.sleep(10)
        } while (System.currentTimeMillis() < deadline)
    }

    private fun assertStayedIdleWithNoLogs() {
        // Sensitive-field early returns must not even start an AI session.
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(AiPanelState.Idle, vm.aiPanelState.value)
        assertTrue(logTexts().isEmpty())
    }
}
