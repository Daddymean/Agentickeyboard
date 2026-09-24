package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Renders the real keyboard at concrete window sizes and measures it.
 *
 * The unit tests in KeyboardMetricsTest pin the arithmetic; these pin that the
 * layout actually consumes it, which is what regressed before: the keyboard was
 * laid out identically in portrait and landscape and did not fit on screen in
 * the latter.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeyboardLayoutWindowSizeTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun showKeyboard(numberRow: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = KeyboardViewModel(KeyboardRepository(AppDatabase.getDatabase(context)))
        viewModel.setNumberRowEnabled(numberRow)
        composeTestRule.setContent {
            MyApplicationTheme {
                // Mirrors how AgenticKeyboardService wraps the IME view tree.
                ProvideKeyboardMetrics {
                    AgenticKeyboardLayout(viewModel = viewModel)
                }
            }
        }
    }

    private fun assertCloseTo(expected: Dp, actual: Dp, what: String) {
        assertTrue(
            "$what: expected ~$expected but measured $actual",
            abs(actual.value - expected.value) <= 1f
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
    fun `portrait keeps full-size keys`() {
        showKeyboard()
        composeTestRule.onNodeWithTag("key_q").assertIsDisplayed()
        composeTestRule.onNodeWithTag("key_space").assertIsDisplayed()
        val key = composeTestRule.onNodeWithTag("key_q").getUnclippedBoundsInRoot()
        assertCloseTo(44.dp, key.height, "portrait key height")
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
    fun `a qwerty row fits within the window in portrait`() {
        showKeyboard()
        val key = composeTestRule.onNodeWithTag("key_q").getUnclippedBoundsInRoot()
        // Ten keys plus the nine gaps between them.
        val rowWidth = key.width.value * 10 + 4f * 9
        assertTrue("row of 10 keys is ${rowWidth}dp wide in a 411dp window", rowWidth <= 411f)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land")
    fun `landscape shrinks the keys so the keyboard still fits`() {
        showKeyboard()
        val key = composeTestRule.onNodeWithTag("key_q").getUnclippedBoundsInRoot()
        assertCloseTo(32.dp, key.height, "landscape key height")
        // The regression this guards: at portrait metrics the keyboard was taller
        // than a landscape window, so the bottom row fell off the screen.
        composeTestRule.onNodeWithTag("key_space").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
    fun `the optional number row renders when the window can afford it`() {
        showKeyboard(numberRow = true)
        composeTestRule.onNodeWithTag("key_1").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w891dp-h411dp-land")
    fun `the optional number row is dropped in a short window`() {
        showKeyboard(numberRow = true)
        composeTestRule.onNodeWithTag("key_1").assertDoesNotExist()
        // The letters it was competing with are still there.
        composeTestRule.onNodeWithTag("key_q").assertIsDisplayed()
    }
}
