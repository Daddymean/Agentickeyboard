package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
class SensitiveFieldAutoCapTest {

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
        composeTestRule.mainClock.advanceTimeByFrame()
    }

    private fun tapQ(): String {
        composeTestRule.onNodeWithTag("key_q").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        return typed.last()
    }

    @Test
    fun `ordinary field auto-capitalizes the first letter`() {
        showKeyboardFor(text)
        composeTestRule.onNodeWithTag("key_q").assertTextEquals("Q")
        assertEquals("Q", tapQ())
    }

    @Test
    fun `password field types the first letter exactly`() {
        showKeyboardFor(text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        composeTestRule.onNodeWithTag("key_q").assertTextEquals("q")
        assertEquals("q", tapQ())
    }

    @Test
    fun `web password field types the first letter exactly`() {
        showKeyboardFor(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        composeTestRule.onNodeWithTag("key_q").assertTextEquals("q")
        assertEquals("q", tapQ())
    }

    @Test
    fun `incognito field types the first letter exactly`() {
        showKeyboardFor(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        composeTestRule.onNodeWithTag("key_q").assertTextEquals("q")
        assertEquals("q", tapQ())
    }

    @Test
    fun `explicit shift still capitalizes in a password field`() {
        showKeyboardFor(text or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        composeTestRule.onNodeWithTag("key_shift").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag("key_q").assertTextEquals("Q")
        assertEquals("Q", tapQ())
    }
}
