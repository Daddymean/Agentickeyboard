package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.text.InputType
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KEYBOARD-002: the learned-correction pause lasts for one input session. It must
 * survive onStartInput restarts of the same field (which call onEditorStarted every
 * time) and clear only when the service reports a new session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LearnedCorrectionPauseTest {

    private val text = InputType.TYPE_CLASS_TEXT

    private fun viewModel(): KeyboardViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return KeyboardViewModel(KeyboardRepository(AppDatabase.getDatabase(context)))
    }

    @Test
    fun `pause is off by default`() {
        assertFalse(viewModel().correctionsPaused.value)
    }

    @Test
    fun `pause survives a restart of the same editor`() {
        val vm = viewModel()
        vm.onEditorStarted("com.example.chat", "Chat", text)
        vm.setCorrectionsPaused(true)

        // onStartInput(restarting = true) calls onEditorStarted again but not onNewInputSession.
        vm.onEditorStarted("com.example.chat", "Chat", text)

        assertTrue(vm.correctionsPaused.value)
    }

    @Test
    fun `a new input session clears the pause`() {
        val vm = viewModel()
        vm.setCorrectionsPaused(true)

        vm.onNewInputSession()

        assertFalse(vm.correctionsPaused.value)
    }

    @Test
    fun `pause is independent of pause learning`() {
        val vm = viewModel()
        val learningBefore = vm.isLearningPaused.value

        vm.setCorrectionsPaused(true)
        assertTrue(vm.isLearningPaused.value == learningBefore)

        vm.setCorrectionsPaused(false)
        assertTrue(vm.isLearningPaused.value == learningBefore)
    }
}
