package io.github.daddymean.agentickeyboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * KEYBOARD-015: minimum touch target for keys in the letter grid.
 *
 * Compose already sends a tap that misses every node to the nearest node whose
 * minimum touch target contains it, and a direct hit always wins. The platform
 * default (48dp) covered the 4dp gaps between keys, because letter keys are
 * narrower than 48dp. It did not cover the gap between rows once keys are 48dp
 * or taller, or the half-key indent beside the middle row, so taps there typed
 * nothing. The target is sized to cover both:
 *  - height: key height plus the padding above and below a row, plus 2dp so the
 *    bands overlap rather than meet;
 *  - width: two key widths plus four gaps, which covers the indent of the
 *    nine-key middle row at any screen width the metrics allow.
 */
fun keyGridTouchTarget(metrics: KeyboardMetrics): DpSize = DpSize(
    width = metrics.keyWidth * 2 + metrics.keyGap * 4,
    height = metrics.keyHeight + metrics.rowGap * 2 + 2.dp
)

/** Applies [keyGridTouchTarget] to the letter grid only (never the command row). */
@Composable
fun KeyGridTouchTargets(metrics: KeyboardMetrics, content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val target = keyGridTouchTarget(metrics)
    val configuration = remember(base, target) {
        object : ViewConfiguration by base {
            override val minimumTouchTargetSize: DpSize = target
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides configuration, content = content)
}
