package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import android.text.InputType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import io.github.daddymean.agentickeyboard.util.EditTimeline
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** LAB-014: backspace offers Rewind; tapping a version hands its text to the service. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class EditRewindTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val viewModelStore = ViewModelStore()
    private var viewModelJob: Job? = null
    private val rewound = mutableListOf<String>()
    private var deletes = 0
    private var hasHistory = true

    @After
    fun tearDown() {
        composeTestRule.runOnIdle { viewModelStore.clear() }
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            shadowOf(Looper.getMainLooper()).idle()
            viewModelJob?.isCompleted != false
        }
        db.close()
    }

    private fun showKeyboard() {
        val viewModel = KeyboardViewModel(KeyboardRepository(db))
        viewModelStore.put("keyboard", viewModel)
        viewModelJob = viewModel.viewModelScope.coroutineContext[Job]
        viewModel.onEditorStarted("com.example.app", "App", InputType.TYPE_CLASS_TEXT, 0)
        composeTestRule.setContent {
            MyApplicationTheme {
                ProvideKeyboardMetrics(keyHeightScale = 1.15f) {
                    AgenticKeyboardLayout(
                        viewModel = viewModel,
                        onDelete = { deletes++ },
                        hasRewind = { hasHistory },
                        rewindVersions = { listOf(EditTimeline.Version("See you tomorrow", "+ tomorrow")) },
                        onRewind = { rewound += it }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `backspace offers rewind and a version restores its text`() {
        showKeyboard()
        composeTestRule.onNodeWithTag("rewind_offer").assertDoesNotExist()
        composeTestRule.onNodeWithTag("key_backspace").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, deletes)
        composeTestRule.onNodeWithTag("rewind_offer").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rewind_0").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("See you tomorrow"), rewound)
        composeTestRule.onNodeWithTag("rewind_strip").assertDoesNotExist()
    }

    @Test
    fun `no history means no rewind chip`() {
        hasHistory = false
        showKeyboard()
        composeTestRule.onNodeWithTag("key_backspace").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("rewind_offer").assertDoesNotExist()
    }
}
