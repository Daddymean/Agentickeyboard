package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The clipboard bar sits above the keys, so anything it renders is vertical space
 * taken from the app being typed into. It may only appear once the user has
 * actually opted in, and never in a sensitive field.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class ClipboardHistoryBarVisibilityTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun showBar(enabled: Boolean, sensitiveField: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = KeyboardRepository(AppDatabase.getDatabase(context))
        composeTestRule.setContent {
            MyApplicationTheme {
                ClipboardHistoryBar(
                    repository = repository,
                    enabled = enabled,
                    paused = false,
                    sensitiveField = sensitiveField,
                    statusMessage = null,
                    onTogglePause = {},
                    onCaptureCurrent = {},
                    onInsert = {},
                    onOpenManager = {}
                )
            }
        }
    }

    @Test
    fun `takes no space at all while clipboard history is off`() {
        showBar(enabled = false)
        composeTestRule.onNodeWithTag("clipboard_history_bar").assertDoesNotExist()
    }

    @Test
    fun `appears once the user has opted in`() {
        showBar(enabled = true)
        composeTestRule.onNodeWithTag("clipboard_history_bar").assertIsDisplayed()
    }

    @Test
    fun `stays hidden in a sensitive field even when enabled`() {
        showBar(enabled = true, sensitiveField = true)
        composeTestRule.onNodeWithTag("clipboard_history_bar").assertDoesNotExist()
    }
}
