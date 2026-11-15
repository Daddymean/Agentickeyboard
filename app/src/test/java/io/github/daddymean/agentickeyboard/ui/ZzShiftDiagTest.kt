package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.room.Room
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.Job

/**
 * KEYBOARD-007: renders the real keyboard at the start of an empty field. Ordinary
 * editors show and type an auto-capitalized "Q"; password, other sensitive and
 * incognito editors show and type exactly "q". Explicit shift still capitalizes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class ZzShiftDiagTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val text = InputType.TYPE_CLASS_TEXT
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val typed = mutableListOf<String>()
    private val viewModelStore = ViewModelStore()
    private var viewModelJob: Job? = null

    @After
    fun tearDown() {
        // Finish the ViewModel's Room work before closing its database. Otherwise
        // an asynchronous initialization transaction can fail in the next test.
        composeTestRule.runOnIdle { viewModelStore.clear() }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            viewModelJob?.isCompleted != false
        }
        db.close()
    }

    private fun showKeyboardFor(inputType: Int, imeOptions: Int = 0) {
        composeTestRule.mainClock.autoAdvance = false
        val viewModel = KeyboardViewModel(KeyboardRepository(db))
        viewModelStore.put("keyboard", viewModel)
        viewModelJob = viewModel.viewModelScope.coroutineContext[Job]
        viewModel.setAutoCapitalizeEnabled(true)
        viewModel.onEditorStarted("com.example.app", "App", inputType, imeOptions)
        composeTestRule.setContent {
            MyApplicationTheme {
                ProvideKeyboardMetrics {
                    AgenticKeyboardLayout(viewModel = viewModel, onKeyPress = { typed += it })
                }
            }
        }
        settle()
    }

    /**
     * The clock is driven by hand. A tap is handled on one frame and the keys
     * redraw on a later one, so advance a few frames rather than exactly one
     * (newer Compose test rules queue that work instead of running it at once).
     */
    private fun settle() = repeat(3) { composeTestRule.mainClock.advanceTimeByFrame() }

    private fun tapQ(): String {
        composeTestRule.onNodeWithTag("key_q").performClick()
        settle()
        return typed.last()
    }

    private val pw get() = text or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private fun q() = composeTestRule.onNodeWithTag("key_q")
    private fun shift() = composeTestRule.onNodeWithTag("key_shift")

    @Test fun d1_touchClick_shiftKeyLabelChanges() {
        showKeyboardFor(pw); shift().performClick(); settle()
        shift().assertTextEquals("⬆")
    }
    @Test fun d2_semanticsClick_capitalizes() {
        showKeyboardFor(pw); shift().performSemanticsAction(SemanticsActions.OnClick); settle()
        q().assertTextEquals("Q")
    }
    @Test fun d3_touchClick_autoAdvance_capitalizes() {
        showKeyboardFor(pw); composeTestRule.mainClock.autoAdvance = true
        shift().performClick(); composeTestRule.waitForIdle()
        q().assertTextEquals("Q")
    }
    @Test fun d4_touchClick_thenOneSecond_capitalizes() {
        showKeyboardFor(pw); shift().performClick(); composeTestRule.mainClock.advanceTimeBy(1000)
        q().assertTextEquals("Q")
    }
    @Test fun d5_downWaitUp_capitalizes() {
        showKeyboardFor(pw); shift().performTouchInput { down(center) }
        composeTestRule.mainClock.advanceTimeBy(100)
        shift().performTouchInput { up() }; settle()
        q().assertTextEquals("Q")
    }
    @Test fun d6_touchClick_shiftLabelIsCapsLock() {
        showKeyboardFor(pw); shift().performClick(); settle()
        shift().assertTextEquals("⇪")
    }
    @Test fun d7_touchClick_shiftLabelStillOff() {
        showKeyboardFor(pw); shift().performClick(); settle()
        shift().assertTextEquals("⇧")
    }
}
