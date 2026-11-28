package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Nikola review of #244: while the StealthInk passphrase bar has focus, suggestion
 * chips must not be computed or shown (and a tap must not insert into the host
 * field), and the mic must be a no-op that captures no audio.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class StealthInkCaptureGuardTest {

    @get:Rule val rule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var viewModel: KeyboardViewModel

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        viewModel = KeyboardViewModel(KeyboardRepository(db))
    }

    @After
    fun tearDown() = db.close()

    private fun settle() = repeat(20) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(10)
    }

    @Test
    fun pausingSuggestionsClearsTheStripAndSkipsComputation() {
        viewModel.setInputText("hel")
        settle()
        assertTrue(
            "prefix 'hel' should produce suggestion chips",
            viewModel.predictiveSuggestions.value.isNotEmpty()
        )

        viewModel.setPredictiveSuggestionsPaused(true)
        assertTrue(viewModel.predictiveSuggestions.value.isEmpty())

        viewModel.setInputText("hell")
        settle()
        assertTrue(
            "suggestions must not be recomputed while the passphrase bar has focus",
            viewModel.predictiveSuggestions.value.isEmpty()
        )

        viewModel.setPredictiveSuggestionsPaused(false)
        settle()
        assertTrue(
            "resuming should recompute from the current editor text",
            viewModel.predictiveSuggestions.value.isNotEmpty()
        )
    }

    @Test
    fun passphraseFocusHidesSuggestionChipsAndIgnoresMic() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = BaseInputConnection(View(context), true)
        var micPresses = 0

        fun sync() {
            val read = raw.getTextBeforeCursor(1000, 0)?.toString().orEmpty()
            viewModel.setInputText(read)
        }

        rule.setContent {
            MyApplicationTheme {
                ProvideKeyboardMetrics(keyHeightScale = 1f) {
                    AgenticKeyboardLayout(
                        viewModel = viewModel,
                        onKeyPress = { raw.commitText(it, 1); sync() },
                        onDelete = { raw.deleteSurroundingText(1, 0); sync() },
                        onMicPress = { micPresses++ },
                        inputConnectionProvider = { raw }
                    )
                }
            }
        }
        rule.waitForIdle()
        sync()

        for (ch in "hel") {
            rule.onNodeWithTag("key_$ch").performTouchInput { down(center); up() }
            rule.mainClock.advanceTimeBy(500)
            rule.waitForIdle()
        }
        settle()
        rule.waitForIdle()
        assertEquals("Hel", raw.editable.toString())
        assertTrue(
            "expected suggestion chips before opening StealthInk",
            rule.onAllNodesWithTag("suggestion_chip").fetchSemanticsNodes().isNotEmpty()
        )

        rule.onNodeWithTag("toggle_ai_tools").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("action_stealth_ink").performScrollTo().performClick()
        rule.waitForIdle()
        settle()
        rule.waitForIdle()

        rule.onAllNodesWithTag("suggestion_chip").assertCountEquals(0)
        assertTrue(
            "computation must also stop, not only the chips hide",
            viewModel.predictiveSuggestions.value.isEmpty()
        )

        val beforeMic = raw.editable.toString()
        rule.onNodeWithTag("key_mic").performClick()
        rule.waitForIdle()
        assertEquals("mic must be a no-op while the passphrase is focused", 0, micPresses)
        assertEquals("mic must not change the host field", beforeMic, raw.editable.toString())

        // A letter goes into the passphrase buffer, not the host field.
        rule.onNodeWithTag("key_x").performTouchInput { down(center); up() }
        rule.mainClock.advanceTimeBy(500)
        rule.waitForIdle()
        assertEquals("Hel", raw.editable.toString())
        assertEquals(0, micPresses)
    }
}
