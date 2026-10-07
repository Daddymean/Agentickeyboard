package io.github.daddymean.agentickeyboard.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import io.github.daddymean.agentickeyboard.util.EditClipboardAction
import io.github.daddymean.agentickeyboard.util.SelectionCommand
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The edit bar takes a row of vertical space from the app being typed into, so
 * it may only render while the user has it open, and the commands it reports
 * must match the chip that was tapped.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
class TextEditBarTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val commands = mutableListOf<SelectionCommand>()
    private val clipboardActions = mutableListOf<EditClipboardAction>()
    private var dismissed = false

    private fun showBar(visible: Boolean = true, hasSelection: Boolean = false) {
        composeTestRule.setContent {
            MyApplicationTheme {
                TextEditBar(
                    visible = visible,
                    hasSelection = hasSelection,
                    onCommand = { commands += it },
                    onClipboardAction = { clipboardActions += it },
                    onDismiss = { dismissed = true }
                )
            }
        }
    }

    @Test
    fun `takes no space at all while closed`() {
        showBar(visible = false)
        composeTestRule.onNodeWithTag("text_edit_bar").assertDoesNotExist()
    }

    @Test
    fun `appears once opened`() {
        showBar()
        composeTestRule.onNodeWithTag("text_edit_bar").assertIsDisplayed()
    }

    @Test
    fun `arrows move the caret while extend is off`() {
        showBar()
        composeTestRule.onNodeWithTag("edit_left").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("edit_right").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("edit_word_left").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("edit_word_right").performScrollTo().performClick()
        assertEquals(
            listOf(
                SelectionCommand.MoveLeft,
                SelectionCommand.MoveRight,
                SelectionCommand.MoveWordLeft,
                SelectionCommand.MoveWordRight
            ),
            commands
        )
    }

    @Test
    fun `arrows extend the selection once extend is latched on`() {
        showBar()
        composeTestRule.onNodeWithTag("edit_extend").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("edit_left").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("edit_word_right").performScrollTo().performClick()
        assertEquals(
            listOf(SelectionCommand.ExtendLeft, SelectionCommand.ExtendWordRight),
            commands
        )
    }

    @Test
    fun `extend latches off again on a second tap`() {
        showBar()
        composeTestRule.onNodeWithTag("edit_extend").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("edit_extend").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("edit_right").performScrollTo().performClick()
        assertEquals(listOf(SelectionCommand.MoveRight), commands)
    }

    @Test
    fun `word line and all report their select commands`() {
        showBar()
        composeTestRule.onNodeWithTag("edit_select_word").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("edit_select_line").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("edit_select_all").performScrollTo().performClick()
        assertEquals(
            listOf(
                SelectionCommand.SelectWord,
                SelectionCommand.SelectLine,
                SelectionCommand.SelectAll
            ),
            commands
        )
    }

    @Test
    fun `cut copy and clear stay hidden until something is selected`() {
        showBar(hasSelection = false)
        composeTestRule.onNodeWithTag("edit_cut").assertDoesNotExist()
        composeTestRule.onNodeWithTag("edit_copy").assertDoesNotExist()
        composeTestRule.onNodeWithTag("edit_collapse").assertDoesNotExist()
        // Paste needs only a caret, so it is always offered.
        composeTestRule.onNodeWithTag("edit_paste").assertExists()
    }

    @Test
    fun `cut and copy appear and report once there is a selection`() {
        showBar(hasSelection = true)
        composeTestRule.onNodeWithTag("edit_cut").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("edit_copy").performScrollTo().performClick()
        assertEquals(
            listOf(EditClipboardAction.Cut, EditClipboardAction.Copy),
            clipboardActions
        )
    }

    @Test
    fun `clear collapses the selection rather than touching the clipboard`() {
        showBar(hasSelection = true)
        composeTestRule.onNodeWithTag("edit_collapse").performScrollTo().performClick()
        assertEquals(listOf(SelectionCommand.Collapse), commands)
        assertEquals(emptyList<EditClipboardAction>(), clipboardActions)
    }

    @Test
    fun `paste reports a clipboard action`() {
        showBar()
        composeTestRule.onNodeWithTag("edit_paste").performScrollTo().performClick()
        assertEquals(listOf(EditClipboardAction.Paste), clipboardActions)
    }

    @Test
    fun `done dismisses the bar`() {
        showBar()
        composeTestRule.onNodeWithTag("edit_done").performScrollTo().performClick()
        assertEquals(true, dismissed)
    }
}
