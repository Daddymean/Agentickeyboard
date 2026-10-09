package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationContextGuardTest {
    private fun viewModel(): KeyboardViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return KeyboardViewModel(KeyboardRepository(AppDatabase.getDatabase(context)))
    }

    @Test fun capturedReplyFailsClosedWithoutValidContextButClipboardStillWorks() {
        val vm = viewModel()
        vm.requestReplyIdeas("When is the meeting?", ephemeralContext = true)
        assertEquals(AiPanelState.Idle, vm.aiPanelState.value)
        vm.requestReplyIdeas("When is the meeting?")
        assertTrue(vm.aiPanelState.value is AiPanelState.ReplyIntent)
    }

    @Test fun changedScreenBlocksIntentGenerationAndAllContextTools() {
        val vm = viewModel()
        var valid = true
        vm.setReplyContextValidator { valid }
        vm.requestReplyIdeas("When is the meeting?", ephemeralContext = true)
        assertTrue((vm.aiPanelState.value as AiPanelState.ReplyIntent).ephemeralContext)
        valid = false
        vm.chooseReplyIntent(null)
        // No AI launch replaces the intent panel when validation fails.
        assertTrue(vm.aiPanelState.value is AiPanelState.ReplyIntent)
        vm.dismissResults()
        vm.summarizeMessage("An incoming message with enough words for a useful summary.", ephemeralContext = true)
        vm.translateText("Message", ephemeralContext = true)
        vm.explainText("Message", ephemeralContext = true)
        assertEquals(AiPanelState.Idle, vm.aiPanelState.value)
    }

    @Test fun secureAndIncognitoEditorsSuppressCapturedReplyEvenWithValidLease() {
        val vm = viewModel()
        vm.setReplyContextValidator { true }
        vm.onEditorStarted(null, "", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        vm.requestReplyIdeas("Private message", ephemeralContext = true)
        assertEquals(AiPanelState.Idle, vm.aiPanelState.value)
        vm.onEditorStarted(null, "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        vm.requestReplyIdeas("Private message", ephemeralContext = true)
        assertEquals(AiPanelState.Idle, vm.aiPanelState.value)
    }
}
