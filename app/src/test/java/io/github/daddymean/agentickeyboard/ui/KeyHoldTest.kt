package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import android.text.InputType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * KEYBOARD-016: holding a letter past the long-press delay never loses it.
 * Releasing without sliding onto an accent types the base letter; sliding onto
 * an accent types only that accent.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class KeyHoldTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val typed = mutableListOf<String>()
    private val viewModelStore = ViewModelStore()
    private var viewModelJob: Job? = null

    @After
    fun tearDown() {
        // Finish the ViewModel's Room work before closing its database.
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
                    AgenticKeyboardLayout(viewModel = viewModel, onKeyPress = { typed += it })
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** What was typed, lower-cased: the empty editor starts with auto-capitalisation on. */
    private fun typedText(): String = typed.joinToString("").lowercase()

    private fun keyCentre(tag: String): Offset {
        val size = composeTestRule.onNodeWithTag(tag).fetchSemanticsNode().size
        return Offset(size.width / 2f, size.height / 2f)
    }

    private fun variantNode(variant: String) = composeTestRule.onNode(
        hasTestTag("key_variant_$variant") or hasTestTag("key_variant_${variant.uppercase()}")
    )

    /** Holds [tag] for [holdMs], then releases where it went down. */
    private fun hold(tag: String, holdMs: Long) {
        val centre = keyCentre(tag)
        composeTestRule.onNodeWithTag(tag).performTouchInput {
            down(centre)
            advanceEventTime(holdMs)
            moveTo(centre)
            up()
        }
        composeTestRule.waitForIdle()
    }

    /** Holds [tag] until the accents show, then slides onto [variant] and releases. */
    private fun holdAndSlideTo(tag: String, variant: String) {
        val centre = keyCentre(tag)
        val key = composeTestRule.onNodeWithTag(tag)
        key.performTouchInput {
            down(centre)
            advanceEventTime(HOLD_MS)
            moveTo(centre)
        }
        composeTestRule.waitForIdle()
        val keyNode = key.fetchSemanticsNode()
        val cell = variantNode(variant).fetchSemanticsNode()
        val target = cell.positionOnScreen - keyNode.positionOnScreen +
            Offset(cell.size.width / 2f, cell.size.height / 2f)
        key.performTouchInput {
            moveTo(Offset(centre.x, (centre.y + target.y) / 2))
            moveTo(target)
            up()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `holding an accent letter past the long-press delay types the letter`() {
        showKeyboard()
        for (ms in listOf(600L, 1_000L, 2_000L)) {
            typed.clear()
            hold("key_o", ms)
            assertEquals("hold o for $ms ms", "o", typedText())
            variantNode("ó").assertDoesNotExist()
        }
    }

    @Test
    fun `a short tap on an accent letter types it once`() {
        showKeyboard()
        hold("key_e", 40)
        assertEquals("e", typedText())
    }

    @Test
    fun `sliding onto an accent types only the accent`() {
        showKeyboard()
        // e sits mid-row; a sits at the edge, where the popup is pushed inside the window.
        for ((tag, variant) in listOf("key_e" to "é", "key_e" to "ë", "key_e" to "è", "key_a" to "à", "key_a" to "ä", "key_n" to "ñ")) {
            typed.clear()
            holdAndSlideTo(tag, variant)
            assertEquals("$tag -> $variant", variant, typedText())
            variantNode(variant).assertDoesNotExist()
        }
    }

    @Test
    fun `sliding back onto the key types the base letter`() {
        showKeyboard()
        val centre = keyCentre("key_a")
        val key = composeTestRule.onNodeWithTag("key_a")
        key.performTouchInput {
            down(centre)
            advanceEventTime(HOLD_MS)
            moveTo(centre)
        }
        composeTestRule.waitForIdle()
        key.performTouchInput {
            moveTo(Offset(centre.x, -centre.y))
            moveTo(centre)
            up()
        }
        composeTestRule.waitForIdle()
        assertEquals("a", typedText())
    }

    @Test
    fun `holding the period key types a period`() {
        showKeyboard()
        hold("key_period", 1_000L)
        assertEquals(".", typedText())
    }

    private companion object {
        const val HOLD_MS = 800L
    }
}
