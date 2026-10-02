package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.daddymean.agentickeyboard.SetupTab
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
 * Renders the real keyboard and the companion app's setup screen to PNGs so the UI
 * can be looked at without a device.
 *
 * Roborazzi writes images only in record mode, which CI enables with
 * `-Proborazzi.test.record=true` and uploads as the `ui-screenshots` artifact. In a
 * plain test run these still compose the screens, so a crash while rendering fails
 * the build either way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [35])
class KeyboardScreenshotTest {

    @get:Rule val composeTestRule = createComposeRule()

    private fun capture(name: String, content: @Composable (KeyboardViewModel) -> Unit) {
        // The keyboard keeps scheduling frames (animations, Room flows), so drive the
        // clock by hand as KeyboardLayoutWindowSizeTest does.
        composeTestRule.mainClock.autoAdvance = false
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = KeyboardViewModel(KeyboardRepository(AppDatabase.getDatabase(context)))
        composeTestRule.setContent { MyApplicationTheme { content(viewModel) } }
        composeTestRule.mainClock.advanceTimeBy(500)
        composeTestRule.onRoot().captureRoboImage(filePath = "build/outputs/roborazzi/$name.png")
    }

    private val keyboard: @Composable (KeyboardViewModel) -> Unit = { viewModel ->
        // Mirrors how AgenticKeyboardService wraps the IME view tree.
        ProvideKeyboardMetrics { AgenticKeyboardLayout(viewModel = viewModel) }
    }

    @Test
    fun keyboardLight() = capture("keyboard_light", keyboard)

    @Test
    @Config(qualifiers = "+night")
    fun keyboardDark() = capture("keyboard_dark", keyboard)

    @Test
    @Config(qualifiers = "+land")
    fun keyboardLandscape() = capture("keyboard_landscape", keyboard)

    @Test
    fun setupScreen() = capture("setup_screen") { viewModel -> SetupTab(viewModel) }
}
