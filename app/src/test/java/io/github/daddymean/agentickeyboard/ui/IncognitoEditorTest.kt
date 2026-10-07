package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
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
 * KEYBOARD-004: an editor that sets IME_FLAG_NO_PERSONALIZED_LEARNING goes through
 * the same sensitive-field state as a password field, which is what gates
 * learning, clipboard capture and AI elsewhere in the keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IncognitoEditorTest {

    private val text = InputType.TYPE_CLASS_TEXT

    private fun viewModel(): KeyboardViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return KeyboardViewModel(KeyboardRepository(AppDatabase.getDatabase(context)))
    }

    @Test
    fun `incognito editor is treated as sensitive and the next ordinary editor is not`() {
        val vm = viewModel()

        vm.onEditorStarted(null, "", text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertTrue(vm.isSensitiveField.value)

        vm.onEditorStarted(null, "", text, EditorInfo.IME_ACTION_SEND)
        assertFalse(vm.isSensitiveField.value)
    }

    @Test
    fun `password editor stays sensitive with the default options argument`() {
        val vm = viewModel()
        vm.onEditorStarted(null, "", text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertTrue(vm.isSensitiveField.value)
    }
}
