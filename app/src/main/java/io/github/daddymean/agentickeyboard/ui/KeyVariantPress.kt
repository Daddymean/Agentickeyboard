package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import io.github.daddymean.agentickeyboard.ui.theme.LocalKeyboardColors

/*
 * KEYBOARD-016: a held key never loses its letter.
 *
 * A long press still opens the accent popup, but the choice is made by sliding:
 * releasing on an accent types that accent, releasing anywhere else types the
 * base letter. Before this, a press held past the system long-press delay
 * (400 ms on "Short") opened the popup and typed nothing.
 */

internal val VariantCellWidth = 36.dp
internal val VariantCellHeight = 40.dp
private val VariantPopupPadding = 4.dp
private val VariantPopupGap = 6.dp

/** Where the accent popup sits, in window pixels, so a slide can be hit-tested against it. */
internal class VariantPopupGeometry {
    /** Window bounds of the key the popup belongs to. */
    var keyBounds: Rect? = null
    /** Window bounds of the popup itself. */
    var popupBounds: Rect? = null
    var cellWidthPx: Float = 0f
    var paddingPx: Float = 0f

    /**
     * The accent under [keyLocal] (a position relative to the key), or null when
     * the finger is still on the key or away from the popup.
     */
    fun variantAt(keyLocal: Offset, count: Int): Int? {
        val key = keyBounds ?: return null
        val popup = popupBounds ?: return null
        if (count <= 0 || cellWidthPx <= 0f) return null
        val x = key.left + keyLocal.x
        val y = key.top + keyLocal.y
        // On the key itself: no accent chosen.
        if (y >= key.top) return null
        // The band above the popup and the gap down to the key count as the popup,
        // so a natural upward slide reaches it.
        if (y < popup.top - popup.height) return null
        if (x < popup.left - cellWidthPx / 2 || x > popup.right + cellWidthPx / 2) return null
        return ((x - popup.left - paddingPx) / cellWidthPx).toInt().coerceIn(0, count - 1)
    }
}

/** Places the popup centred above the key, kept inside the window, and records where it went. */
private class VariantPopupPositionProvider(
    private val gapPx: Int,
    private val geometry: VariantPopupGeometry
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(0, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(0)
        geometry.keyBounds = Rect(
            anchorBounds.left.toFloat(), anchorBounds.top.toFloat(),
            anchorBounds.right.toFloat(), anchorBounds.bottom.toFloat()
        )
        geometry.popupBounds = Rect(
            x.toFloat(), y.toFloat(),
            (x + popupContentSize.width).toFloat(), (y + popupContentSize.height).toFloat()
        )
        return IntOffset(x, y)
    }
}

@Composable
internal fun KeyVariantPopup(
    variants: List<String>,
    selected: Int?,
    geometry: VariantPopupGeometry,
    onVariant: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalKeyboardColors.current
    val density = LocalDensity.current
    geometry.cellWidthPx = with(density) { VariantCellWidth.toPx() }
    geometry.paddingPx = with(density) { VariantPopupPadding.toPx() }
    val provider = remember(geometry, density) {
        VariantPopupPositionProvider(with(density) { VariantPopupGap.roundToPx() }, geometry)
    }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(8.dp), color = colors.popup, shadowElevation = 6.dp) {
            Row(modifier = Modifier.padding(VariantPopupPadding)) {
                variants.forEachIndexed { index, variant ->
                    Box(
                        modifier = Modifier
                            .size(VariantCellWidth, VariantCellHeight)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (index == selected) colors.keyActive else colors.popup)
                            .clickable { onVariant(variant) }
                            .testTag("key_variant_$variant"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(variant, fontSize = 16.sp, color = colors.text)
                    }
                }
            }
        }
    }
}

/**
 * Tap and hold handling for a key. A release before the long-press delay is a tap.
 * When [longPressEnabled], holding past the delay calls [onLongPress]; every later
 * move is reported to [onHoldMove], and the release to [onHoldEnd] with the down
 * position and the last position. All events of a held press are consumed, so
 * the slide never starts swipe typing.
 */
internal suspend fun PointerInputScope.detectKeyPress(
    longPressEnabled: () -> Boolean,
    onTap: (Offset) -> Unit,
    onLongPress: () -> Unit,
    onHoldMove: (Offset) -> Unit,
    onHoldEnd: (down: Offset, last: Offset) -> Unit
) = awaitEachGesture {
    val down = awaitFirstDown()
    down.consume()
    if (!longPressEnabled()) {
        val up = waitForUpOrCancellation() ?: return@awaitEachGesture
        up.consume()
        onTap(up.position)
        return@awaitEachGesture
    }
    var cancelled = false
    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        waitForUpOrCancellation().also { if (it == null) cancelled = true }
    }
    if (cancelled) return@awaitEachGesture
    if (up != null) {
        up.consume()
        onTap(up.position)
        return@awaitEachGesture
    }
    onLongPress()
    var last = down.position
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        change.consume()
        last = change.position
        if (!change.pressed) break
        onHoldMove(last)
    }
    onHoldEnd(down.position, last)
}
