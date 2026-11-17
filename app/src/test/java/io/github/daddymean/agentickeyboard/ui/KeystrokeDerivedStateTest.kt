package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * LAB-008 guard: the keyboard no longer reads the draft directly in its body, so
 * these check that auto-capitalisation and the command palette still follow the
 * draft through derivedStateOf as the IME pushes new text.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class KeystrokeDerivedStateTest {

    @get:Rule val rule = createComposeRule()

    private lateinit var viewModel: KeyboardViewModel

    private fun show() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        viewModel = KeyboardViewModel(KeyboardRepository(AppDatabase.getDatabase(context)))
        rule.setContent {
            MyApplicationTheme {
                ProvideKeyboardMetrics(keyHeightScale = 1f) { AgenticKeyboardLayout(viewModel = viewModel) }
            }
        }
        rule.waitForIdle()
    }

    private fun type(text: String) {
        viewModel.setInputText(text)
        rule.waitForIdle()
    }

    private fun shows(label: String) =
        rule.onAllNodesWithText(label, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun autoCapFollowsTheDraft() {
        show()
        type("")
        assertTrue("empty field starts a sentence", shows("Q"))
        type("Hi")
        assertTrue("mid-word is lower case", shows("q"))
        assertTrue(!shows("Q"))
        type("Hi. ")
        assertTrue("after a full stop it capitalises again", shows("Q"))
        type("Hi. t")
        assertTrue(shows("q"))
    }

    @Test
    fun commandPaletteFollowsTheDraft() {
        show()
        type("hello")
        assertEquals(0, rule.onAllNodesWithTag("command_palette").fetchSemanticsNodes().size)
        type("/fi")
        assertTrue(shows("/firm Firm & direct"))
        type("/firm")
        assertTrue(shows("/firm Firm & direct"))
        type("/x")
        rule.mainClock.advanceTimeBy(1_000)
        assertEquals(0, rule.onAllNodesWithTag("command_palette").fetchSemanticsNodes().size)
    }
}
