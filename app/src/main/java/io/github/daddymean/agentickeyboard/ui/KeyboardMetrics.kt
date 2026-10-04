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
    // Height of the idle toolbar strip (no AI result showing).
    val toolbarHeight: Dp,
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

private const val KEY_SCALE_MIN = 0.8f
private const val KEY_SCALE_MAX = 1.5f
/** Largest share of the window a boosted keyboard may take. */
private const val KEYBOARD_HEIGHT_BUDGET = 0.45f
/** Number row + three letter rows + bottom command row. */
private const val MAX_KEY_ROWS = 5

/**
 * Derives the keyboard's size budget from the current window. Pure arithmetic so
 * it can be exercised by JVM unit tests.
 */
fun keyboardMetricsFor(
    screenWidthDp: Int,
    screenHeightDp: Int,
    keyHeightScale: Float = 1f
): KeyboardMetrics {
    val gaps = KEY_GAP_DP * (KEYS_PER_ROW - 1)
    val usableWidth = screenWidthDp - SIDE_MARGIN_DP - gaps
    val keyWidth = (usableWidth / KEYS_PER_ROW).coerceIn(MIN_KEY_WIDTH_DP, MAX_KEY_WIDTH_DP)
    val compact = screenHeightDp < COMPACT_HEIGHT_THRESHOLD_DP
    // Width is already the full screen split ten ways, so "bigger keys" can only
    // mean taller ones. A boost is granted only while the tallest layout (five key
    // rows with the number row) stays within KEYBOARD_HEIGHT_BUDGET of the window,
    // so borderline split-screen heights never squeeze out the field being edited.
    val baseKeyHeight = if (compact) 32f else 44f
    val rowGap = if (compact) 2f else 3f
    val toolbarHeight = if (compact) 36f else 44f
    val bottomPadding = if (compact) 2f else 8f
    val requested = baseKeyHeight * keyHeightScale.coerceIn(KEY_SCALE_MIN, KEY_SCALE_MAX)
    val fitting = (screenHeightDp * KEYBOARD_HEIGHT_BUDGET - toolbarHeight - bottomPadding) /
        MAX_KEY_ROWS - rowGap * 2
    val keyHeight = if (requested <= baseKeyHeight) requested else minOf(requested, maxOf(fitting, baseKeyHeight))
    return KeyboardMetrics(
        keyWidth = keyWidth.dp,
        keyHeight = Math.round(keyHeight).dp,
        rowGap = rowGap.dp,
        keyGap = KEY_GAP_DP.dp,
        shelfHeight = if (compact) 40.dp else 64.dp,
        toolbarHeight = toolbarHeight.dp,
        bottomPadding = bottomPadding.dp,
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
fun ProvideKeyboardMetrics(keyHeightScale: Float = 1f, content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val metrics = remember(configuration.screenWidthDp, configuration.screenHeightDp, keyHeightScale) {
        keyboardMetricsFor(configuration.screenWidthDp, configuration.screenHeightDp, keyHeightScale)
    }
    CompositionLocalProvider(LocalKeyboardMetrics provides metrics) {
        content()
    }
}
