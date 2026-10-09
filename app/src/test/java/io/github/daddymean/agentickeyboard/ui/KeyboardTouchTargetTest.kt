package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.os.Looper
import android.text.InputType
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isRoot
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
 * KEYBOARD-015: renders the real keyboard and taps at measured positions. A tap
 * at a key's visual centre types that key; a tap that lands between two rows or
 * beside the middle row types the nearest letter instead of nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-port-xxhdpi")
class KeyboardTouchTargetTest {

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

    private data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
        val cx get() = (l + r) / 2
        val cy get() = (t + b) / 2
    }

    private lateinit var keys: Map<Char, Box>

    private fun showKeyboard(keyHeightScale: Float) {
        val viewModel = KeyboardViewModel(KeyboardRepository(db))
        viewModelStore.put("keyboard", viewModel)
        viewModelJob = viewModel.viewModelScope.coroutineContext[Job]
        viewModel.onEditorStarted("com.example.app", "App", InputType.TYPE_CLASS_TEXT, 0)
        composeTestRule.setContent {
            MyApplicationTheme {
                ProvideKeyboardMetrics(keyHeightScale = keyHeightScale) {
                    AgenticKeyboardLayout(viewModel = viewModel, onKeyPress = { typed += it })
                }
            }
        }
        composeTestRule.waitForIdle()
        keys = LETTERS.associateWith { c ->
            val d = composeTestRule.onNodeWithTag("key_$c").getUnclippedBoundsInRoot()
            Box(d.left.value, d.top.value, d.right.value, d.bottom.value)
        }
    }

    /** Taps at ([xDp], [yDp]) in root coordinates; returns what was typed, lower-cased. */
    private fun tapAt(xDp: Float, yDp: Float): String {
        typed.clear()
        val density = composeTestRule.density.density
        composeTestRule.onAllNodes(isRoot())[0].performTouchInput {
            down(Offset(xDp * density, yDp * density))
            up()
        }
        composeTestRule.waitForIdle()
        return typed.joinToString("").lowercase()
    }

    private fun checkGrid() {
        for (c in LETTERS) assertEquals("centre of $c", c.toString(), tapAt(keys[c]!!.cx, keys[c]!!.cy))

        // Midway between rows: must type one of the two letters above/below, never nothing.
        for ((upper, lower) in listOf("qwertyuio" to "asdfghjkl", "sdfghjk" to "zxcvbnm")) {
            for (i in upper.indices) {
                val u = keys[upper[i]]!!
                val d = keys[lower[i]]!!
                val result = tapAt(u.cx, (u.b + d.t) / 2)
                assertTrue("between ${upper[i]} and ${lower[i]} typed '$result'",
                    result == upper[i].toString() || result == lower[i].toString())
            }
        }

        // Middle-row indents: left of 'a' types a, right of 'l' types l.
        val a = keys['a']!!
        val l = keys['l']!!
        var x = 1f
        while (x < a.l) { assertEquals("left indent at ${x}dp", "a", tapAt(x, a.cy)); x += 4f }
        x = l.r + 1f
        while (x < 410f) { assertEquals("right indent at ${x}dp", "l", tapAt(x, l.cy)); x += 4f }

        // Horizontal gap between two keys still goes to a neighbour.
        val s = keys['s']!!
        val gap = tapAt((a.r + s.l) / 2, a.cy)
        assertTrue("gap a|s typed '$gap'", gap == "a" || gap == "s")
    }

    @Test
    fun `taps between rows and beside the middle row type the nearest letter at the default size`() {
        showKeyboard(1.15f)
        checkGrid()
    }

    @Test
    fun `taps between rows type the nearest letter at extra large size`() {
        showKeyboard(1.3f)
        checkGrid()
    }

    private companion object {
        const val LETTERS = "qwertyuiopasdfghjklzxcvbnm"
    }
}
