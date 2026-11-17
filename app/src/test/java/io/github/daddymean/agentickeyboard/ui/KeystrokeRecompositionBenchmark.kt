package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * LAB-008 probe: how much of the keyboard recomposes per keystroke.
 *
 * Renders the real keyboard (Robolectric), then types a sentence through
 * viewModel.setInputText, the same call the IME makes from onUpdateSelection,
 * advancing one frame per keystroke. A composition tracer counts every
 * restartable composable that runs, and how often AgenticKeyboardLayout's own
 * body re-runs. Counts are deterministic; the wall time is box-JVM only.
 * Prints for a human, asserts nothing.
 */
@OptIn(InternalComposeTracingApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KeystrokeRecompositionBenchmark {

    @get:Rule val composeTestRule = createComposeRule()

    private var counting = false
    private var calls = 0
    private var layoutBodies = 0

    private val tracer = object : CompositionTracer {
        override fun isTraceInProgress(): Boolean = true
        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (!counting) return
            calls++
            if (info.startsWith("io.github.daddymean.agentickeyboard.ui.AgenticKeyboardLayout ")) layoutBodies++
        }
        override fun traceEventEnd() = Unit
    }

    @After
    fun clearTracer() = Composer.setTracer(null)

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp-port")
    fun benchmarkKeystrokeRecomposition() {
        Composer.setTracer(tracer)
        composeTestRule.mainClock.autoAdvance = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = KeyboardViewModel(KeyboardRepository(AppDatabase.getDatabase(context)))
        composeTestRule.setContent {
            MyApplicationTheme {
                ProvideKeyboardMetrics(keyHeightScale = 1f) {
                    AgenticKeyboardLayout(viewModel = viewModel)
                }
            }
        }
        repeat(5) { composeTestRule.mainClock.advanceTimeByFrame() }

        val sentence = "see you at the game tomorrow night"
        // Warm-up pass, then the measured pass (clear the field in between).
        for (pass in 0..1) {
            viewModel.setInputText("")
            repeat(3) { composeTestRule.mainClock.advanceTimeByFrame() }
            counting = pass == 1
            calls = 0; layoutBodies = 0
            val started = System.nanoTime()
            for (n in 1..sentence.length) {
                viewModel.setInputText(sentence.take(n))
                composeTestRule.mainClock.advanceTimeByFrame()
            }
            val ms = (System.nanoTime() - started) / 1e6
            if (pass == 1) {
                val keys = sentence.length
                println(
                    "LAB-008 per keystroke over $keys keys (Robolectric, box JVM): " +
                        "composables run ${"%.1f".format(calls.toDouble() / keys)}, " +
                        "AgenticKeyboardLayout body re-runs ${"%.2f".format(layoutBodies.toDouble() / keys)}, " +
                        "wall ${"%.2f".format(ms / keys)} ms"
                )
            }
        }
        counting = false
    }
}
