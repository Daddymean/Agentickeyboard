package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.github.daddymean.agentickeyboard.util.VisibleContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ConversationContextPreviewTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewRequiresSelectionAndExplicitAttachAndPassesOnlySelectedBlocks() {
        var confirmed: List<Int>? = null
        compose.setContent {
            Box(Modifier.height(260.dp)) {
                ConversationContextPreview(
                    VisibleContext("chat", 1, listOf("Contact name", "Bring the invoices?")),
                    "Light", onConfirm = { confirmed = it }, onClear = {})
            }
        }
        compose.onNodeWithText("Attach selected text").assertIsNotEnabled()
        assertEquals(null, confirmed)
        compose.onNodeWithTag("context_block_1").performClick()
        assertEquals(null, confirmed)
        compose.onNodeWithText("Attach selected text").performClick()
        assertEquals(listOf(1), confirmed)
    }
}
