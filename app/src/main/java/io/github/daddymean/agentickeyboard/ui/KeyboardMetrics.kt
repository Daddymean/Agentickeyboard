package io.github.daddymean.agentickeyboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Size budget for the on-screen keyboard.
 *
 * An IME shares the screen with the app being typed into, so none of these are
 * hard-coded constants: key size, row spacing and the AI shelf are all derived
 * from the window the keyboard was actually given. A phone held in landscape
 * offers only ~360-410dp of height in *total*, which the portrait metrics would
 * consume entirely, leaving nothing for the text field being edited.
 */
data class KeyboardMetrics(
    val keyWidth: Dp,
    val keyHeight: Dp,
    val rowGap: Dp,
    val keyGap: Dp,
    val shelfHeight: Dp,
    val bottomPadding: Dp,
    /** True when the window is too short to afford the full portrait layout. */
    val isCompact: Boolean
) {
    /** Shift and backspace. */
    val wideKeyWidth: Dp get() = keyWidth * WIDE_KEY_RATIO

    /** Mode switch (?123 / ABC) and Enter. */
    val extraWideKeyWidth: Dp get() = keyWidth * EXTRA_WIDE_KEY_RATIO

    /** Privacy and voice keys. */
    val mediumKeyWidth: Dp get() = keyWidth * MEDIUM_KEY_RATIO

    /** Punctuation keys flanking the space bar. */
    val smallKeyWidth: Dp get() = keyWidth * SMALL_KEY_RATIO
}

// Ratios are expressed against the 32dp base key so the proportions the layout
// was designed with survive at any key width.
private const val WIDE_KEY_RATIO = 1.375f       // 44dp at a 32dp base
private const val EXTRA_WIDE_KEY_RATIO = 1.75f  // 56dp
private const val MEDIUM_KEY_RATIO = 1.25f      // 40dp
private const val SMALL_KEY_RATIO = 1.125f      // 36dp

/** Letters in the widest QWERTY row; every other row is narrower than this. */
private const val KEYS_PER_ROW = 10
private const val MIN_KEY_WIDTH_DP = 28
private const val MAX_KEY_WIDTH_DP = 64
private const val SIDE_MARGIN_DP = 4
private const val KEY_GAP_DP = 4

/**
 * Windows shorter than this cannot afford the full portrait layout. Chosen on
 * height rather than orientation so split-screen and freeform windows compact
 * too, while a tablet in landscape (still ~800dp tall) does not.
 */
const val COMPACT_HEIGHT_THRESHOLD_DP = 480

/**
 * Derives the keyboard's size budget from the current window. Pure arithmetic so
 * it can be exercised by JVM unit tests.
 */
fun keyboardMetricsFor(screenWidthDp: Int, screenHeightDp: Int): KeyboardMetrics {
    val gaps = KEY_GAP_DP * (KEYS_PER_ROW - 1)
    val usableWidth = screenWidthDp - SIDE_MARGIN_DP - gaps
    val keyWidth = (usableWidth / KEYS_PER_ROW).coerceIn(MIN_KEY_WIDTH_DP, MAX_KEY_WIDTH_DP)
    val compact = screenHeightDp < COMPACT_HEIGHT_THRESHOLD_DP
    return KeyboardMetrics(
        keyWidth = keyWidth.dp,
        keyHeight = if (compact) 32.dp else 44.dp,
        rowGap = if (compact) 2.dp else 3.dp,
        keyGap = KEY_GAP_DP.dp,
        shelfHeight = if (compact) 40.dp else 64.dp,
        bottomPadding = if (compact) 2.dp else 8.dp,
        isCompact = compact
    )
}

/** Portrait phone defaults, used when no provider is present (previews, tests). */
val LocalKeyboardMetrics = staticCompositionLocalOf { keyboardMetricsFor(360, 800) }

/**
 * Root provider for the keyboard's size budget. Wrap the IME view tree once, at
 * the top; descendants read LocalKeyboardMetrics.current.
 */
@Composable
fun ProvideKeyboardMetrics(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val metrics = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        keyboardMetricsFor(configuration.screenWidthDp, configuration.screenHeightDp)
    }
    CompositionLocalProvider(LocalKeyboardMetrics provides metrics) {
        content()
    }
}
