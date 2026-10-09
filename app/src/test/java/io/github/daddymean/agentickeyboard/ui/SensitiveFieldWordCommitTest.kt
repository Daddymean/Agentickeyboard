package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * KEYBOARD-006: with real seeded rules, the view model must not rewrite committed
 * words (shortcut or learned correction) or apply the double-space period while a
 * password, other sensitive or incognito editor is focused, and must resume normal
 * behavior in the next ordinary editor.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SensitiveFieldWordCommitTest {

    private val text = InputType.TYPE_CLASS_TEXT
    private lateinit var db: AppDatabase
    private lateinit var vm: KeyboardViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val repository = KeyboardRepository(db)
        runBlocking {
            repository.insertCorrection(LearnedCorrection(typo = "teh", correction = "the"))
            repository.insertShortcut(ShortcutTemplate(shortcut = "brb", template = "be right back"))
        }
        vm = KeyboardViewModel(repository)
        awaitSeededRules()
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** The rule flows are Room-backed; wait until both seeded rows reach the view model. */
    private fun awaitSeededRules() {
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.learnedCorrections.value.isEmpty() || vm.shortcuts.value.isEmpty()) {
            check(System.currentTimeMillis() < deadline) { "seeded rules never reached the view model" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun assertRewritesActive() {
        assertEquals(WordReplacement("the", fromLearnedRule = true), vm.resolveWordCommit("teh"))
        assertEquals("be right back", vm.resolveWordCommit("brb")?.replacement)
        assertTrue(vm.allowsSmartSpaceRewrites())
    }

    private fun assertRewritesSuppressed() {
        assertTrue(vm.isSensitiveField.value)
        assertNull(vm.resolveWordCommit("teh"))
        assertNull(vm.resolveWordCommit("brb"))
        assertFalse(vm.allowsSmartSpaceRewrites())
    }

    @Test
    fun `ordinary editor applies seeded shortcut and learned correction`() {
        vm.onEditorStarted("com.example.chat", "Chat", text)
        assertRewritesActive()
    }

    @Test
    fun `password variations suppress every smart-space rewrite`() {
        listOf(
            text or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ).forEach { inputType ->
            vm.onEditorStarted("com.example.bank", "Bank", inputType)
            assertRewritesSuppressed()
        }
    }

    @Test
    fun `incognito editor suppresses rewrites even with corrections not paused`() {
        vm.onEditorStarted("com.example.browser", "Browser", text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertFalse(vm.correctionsPaused.value)
        assertRewritesSuppressed()
    }

    @Test
    fun `next ordinary editor restores rewrites`() {
        vm.onEditorStarted("com.example.bank", "Bank", text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertRewritesSuppressed()

        vm.onNewInputSession()
        vm.onEditorStarted("com.example.chat", "Chat", text)
        assertFalse(vm.isSensitiveField.value)
        assertRewritesActive()
    }
}
