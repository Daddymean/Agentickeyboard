package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import android.text.InputType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * KEYBOARD-017: the space bar types a space unless the thumb clearly slides
 * sideways; a deliberate slide moves the cursor and types no space.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class SpaceSlideTest {

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

    private val cursorMoves = mutableListOf<Int>()

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
                        onKeyPress = { typed += it },
                        onCursorMove = { cursorMoves += it }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Presses space, slides by ([dxDp], [dyDp]) in small steps, and releases. */
    private fun slideSpace(dxDp: Float, dyDp: Float = 0f) {
        typed.clear()
        cursorMoves.clear()
        val density = composeTestRule.density.density
        val key = composeTestRule.onNodeWithTag("key_space")
        val size = key.fetchSemanticsNode().size
        val start = Offset(size.width / 2f, size.height / 2f)
        key.performTouchInput {
            down(start)
            for (i in 1..STEPS) {
                advanceEventTime(10)
                moveTo(start + Offset(dxDp * density * i / STEPS, dyDp * density * i / STEPS))
            }
            up()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a small slide on space still types a space`() {
        showKeyboard()
        for (dx in listOf(0f, 8f, 12f, 16f, 20f, -20f)) {
            slideSpace(dx)
            assertEquals("slide ${dx}dp", listOf(" "), typed)
            assertEquals("slide ${dx}dp moved the cursor", emptyList<Int>(), cursorMoves)
        }
    }

    @Test
    fun `vertical wobble on space still types a space`() {
        showKeyboard()
        for (dy in listOf(-30f, 20f)) {
            slideSpace(6f, dy)
            assertEquals("wobble ${dy}dp", listOf(" "), typed)
        }
    }

    @Test
    fun `a deliberate slide moves the cursor and types no space`() {
        showKeyboard()
        slideSpace(80f)
        assertEquals(emptyList<String>(), typed)
        assertTrue("cursor moved right: $cursorMoves", cursorMoves.sum() > 0)
        slideSpace(-80f)
        assertEquals(emptyList<String>(), typed)
        assertTrue("cursor moved left: $cursorMoves", cursorMoves.sum() < 0)
    }

    private companion object {
        const val STEPS = 8
    }
}
