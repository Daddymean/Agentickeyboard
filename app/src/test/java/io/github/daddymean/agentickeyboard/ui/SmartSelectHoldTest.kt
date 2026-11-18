package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import android.text.InputType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import io.github.daddymean.agentickeyboard.util.SelectionTransform
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
 * LAB-011: holding space still starts smart select; sliding while held climbs
 * rungs; a tap or an early slide behaves exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class SmartSelectHoldTest {

    @get:Rule val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    private val typed = mutableListOf<String>()
    private val cursorMoves = mutableListOf<Int>()
    private val rungs = mutableListOf<Int>()
    private var starts = 0
    private var ends = 0
    private var available = true
    private val flicks = mutableListOf<Boolean>()
    private val inserted = mutableListOf<String>()
    private val viewModelStore = ViewModelStore()
    private var viewModelJob: Job? = null

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
                        onKeyPress = { typed += it },
                        onCursorMove = { cursorMoves += it },
                        onSmartSelectStart = { starts++; if (available) "Word" else null },
                        onSmartSelectRung = { delta -> rungs += delta; "Rung" },
                        onInsertText = { inserted += it },
                        onSmartSelectEnd = { flick -> ends++; flicks += flick
                            if (flick) listOf(SelectionTransform("→ 7.77 mi", "7.77 mi")) else emptyList() }
                    )
                }
            }
        }
        composeTestRule.waitForIdle()
    }

    /** Presses space, waits [holdMs], slides [dxDp] in steps, releases. */
    private fun press(holdMs: Long, dxDp: Float = 0f, dyDp: Float = 0f) {
        typed.clear(); cursorMoves.clear(); rungs.clear(); starts = 0; ends = 0; flicks.clear()
        val density = composeTestRule.density.density
        val key = composeTestRule.onNodeWithTag("key_space")
        val size = key.fetchSemanticsNode().size
        val start = Offset(size.width / 2f, size.height / 2f)
        key.performTouchInput {
            down(start)
            if (holdMs > 0) {
                // Small wobble events while resting, as a real thumb produces.
                var waited = 0L
                while (waited < holdMs) {
                    advanceEventTime(50); waited += 50
                    moveTo(start + Offset(if (waited % 100 == 0L) 1f else 0f, 0f))
                }
            }
            for (i in 1..STEPS) {
                advanceEventTime(10)
                moveTo(start + Offset(dxDp * density * i / STEPS, dyDp * density * i / STEPS))
            }
            up()
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `a quick tap types a space and never starts smart select`() {
        showKeyboard()
        press(holdMs = 100)
        assertEquals(listOf(" "), typed)
        assertEquals(0, starts)
    }

    @Test
    fun `an early slide still moves the cursor`() {
        showKeyboard()
        press(holdMs = 0, dxDp = 80f)
        assertEquals(emptyList<String>(), typed)
        assertEquals(0, starts)
        assertTrue(cursorMoves.sum() > 0)
    }

    @Test
    fun `holding still starts smart select and types nothing`() {
        showKeyboard()
        press(holdMs = 800)
        assertEquals(1, starts)
        assertEquals(1, ends)
        assertEquals(emptyList<String>(), typed)
        assertEquals(emptyList<Int>(), cursorMoves)
    }

    @Test
    fun `sliding while held climbs and descends rungs instead of moving the cursor`() {
        showKeyboard()
        press(holdMs = 800, dxDp = 80f)
        assertEquals(1, starts)
        assertEquals("rungs $rungs", 2, rungs.sum())
        assertEquals(emptyList<Int>(), cursorMoves)
        assertEquals(emptyList<String>(), typed)
        press(holdMs = 800, dxDp = -40f)
        assertEquals("rungs $rungs", -1, rungs.sum())
    }

    @Test
    fun `when smart select is unavailable a hold still types a space`() {
        available = false
        showKeyboard()
        press(holdMs = 800)
        assertEquals(1, starts)
        assertEquals(0, ends)
        assertEquals(listOf(" "), typed)
    }

    @Test
    fun `a flick up at the end of a hold offers transform chips that replace the selection`() {
        showKeyboard()
        press(holdMs = 800)
        assertEquals(listOf(false), flicks)
        composeTestRule.onNodeWithTag("transform_chips").assertDoesNotExist()
        press(holdMs = 800, dyDp = -60f)
        assertEquals(listOf(true), flicks)
        assertEquals(emptyList<Int>(), cursorMoves)
        assertEquals(emptyList<String>(), typed)
        composeTestRule.onNodeWithTag("transform_chip_0").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("7.77 mi"), inserted)
        composeTestRule.onNodeWithTag("transform_chips").assertDoesNotExist()
    }

    private companion object {
        const val STEPS = 8
    }
}
