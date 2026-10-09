package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import android.text.InputType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
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
 * KEYBOARD-018: overlapping presses type in the order the keys went down,
 * whatever order the fingers lift.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class KeyPressOrderTest {

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

    /** Centre of key [c] in root pixels. */
    private fun centre(c: Char): Offset {
        val b = composeTestRule.onNodeWithTag("key_$c").getUnclippedBoundsInRoot()
        val density = composeTestRule.density.density
        return Offset((b.left + b.right).value / 2 * density, (b.top + b.bottom).value / 2 * density)
    }

    /** Runs a multi-finger gesture on the keyboard; returns what was typed, lower-cased. */
    private fun fingers(block: TouchInjectionScope.() -> Unit): String {
        typed.clear()
        composeTestRule.onAllNodes(isRoot())[0].performTouchInput(block)
        composeTestRule.waitForIdle()
        return typed.joinToString("").lowercase()
    }

    @Test
    fun `two overlapping presses type in press order whichever lifts first`() {
        showKeyboard()
        val a = centre('a')
        val i = centre('i')
        assertEquals("ai", fingers { down(0, a); advanceEventTime(30); down(1, i); advanceEventTime(30); up(1); advanceEventTime(30); up(0) })
        assertEquals("ai", fingers { down(0, a); advanceEventTime(30); down(1, i); advanceEventTime(30); up(0); advanceEventTime(30); up(1) })
        assertEquals("ia", fingers { down(0, i); advanceEventTime(30); down(1, a); advanceEventTime(30); up(1); advanceEventTime(30); up(0) })
    }

    @Test
    fun `three overlapping presses released in reverse type in press order`() {
        showKeyboard()
        val t = centre('t')
        val h = centre('h')
        val e = centre('e')
        assertEquals("the", fingers {
            down(0, t); advanceEventTime(20); down(1, h); advanceEventTime(20); down(2, e); advanceEventTime(20)
            up(2); advanceEventTime(20); up(1); advanceEventTime(20); up(0)
        })
    }

    @Test
    fun `a held accent letter overlapped by the next letter types both in order without accents`() {
        showKeyboard()
        val o = centre('o')
        val n = centre('n')
        assertEquals("on", fingers { down(0, o); advanceEventTime(60); down(1, n); advanceEventTime(900); up(1); advanceEventTime(20); up(0) })
        composeTestRule.onNode(hasTestTag("key_variant_ó") or hasTestTag("key_variant_Ó")).assertDoesNotExist()
    }

    @Test
    fun `a letter and space that overlap type in press order`() {
        showKeyboard()
        val a = centre('a')
        val b = composeTestRule.onNodeWithTag("key_space").getUnclippedBoundsInRoot()
        val density = composeTestRule.density.density
        val space = Offset((b.left + b.right).value / 2 * density, (b.top + b.bottom).value / 2 * density)
        assertEquals("a ", fingers { down(0, a); advanceEventTime(30); down(1, space); advanceEventTime(30); up(1); advanceEventTime(30); up(0) })
        assertEquals(" a", fingers { down(0, space); advanceEventTime(30); down(1, a); advanceEventTime(30); up(1); advanceEventTime(30); up(0) })
    }

    @Test
    fun `separate quick taps are unchanged`() {
        showKeyboard()
        val keys = "the".map { centre(it) }
        assertEquals("the", fingers {
            for (k in keys) { down(0, k); advanceEventTime(30); up(0); advanceEventTime(30) }
        })
    }
}
