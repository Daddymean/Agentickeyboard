package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sign

/*
 * KEYBOARD-017: the space bar types a space unless the thumb clearly slides.
 *
 * Before this, the space bar combined a click with a drag detector. Any slide past
 * the platform touch slop (about 8 dp) turned the press into a cursor drag and
 * typed no space, so a hurried thumb dropped spaces ("iam", "inparticulr").
 */

/** Horizontal travel that turns a space press into cursor movement. */
internal val SpaceCursorSlideThreshold = 24.dp

/** Pixels of further travel per cursor step once sliding (unchanged from before). */
internal const val SPACE_CURSOR_STEP_PX = 48f

/** LAB-011: travel per smart-select rung once the space bar is held. */
internal val SmartSelectRungDistance = 36.dp

/**
 * LAB-011: callbacks for holding the space bar still, then sliding to climb the
 * smart-select ladder. [onHold] returns false when there is nothing to select
 * (sensitive field, empty field), and the press then behaves as before.
 */
class SpaceHoldCallbacks(
    val onHold: () -> Boolean,
    val onRung: (Int) -> Unit,
    /** [flickUp] is true when the thumb lifted after flicking up: offer transforms. */
    val onHoldEnd: (flickUp: Boolean) -> Unit
)

/** LAB-013: upward travel during a hold that counts as a flick. */
internal val SmartSelectFlickDistance = 40.dp

/**
 * One gesture for a key that can also slide the cursor. A release before the
 * finger has moved [SpaceCursorSlideThreshold] sideways is a tap, whatever the
 * vertical wobble. Past the threshold the press becomes a cursor slide: [onSlide]
 * gets whole steps of [SPACE_CURSOR_STEP_PX], counted from the threshold, and the
 * release types nothing.
 */
internal suspend fun PointerInputScope.detectTapOrHorizontalSlide(
    order: KeyPressOrder? = null,
    onTap: () -> Unit,
    onSlide: (Int) -> Unit,
    hold: (() -> SpaceHoldCallbacks?)? = null
) = awaitEachGesture {
    val down = awaitFirstDown()
    down.consume()
    // KEYBOARD-018: space joins the press order; if a key goes down while space is
    // held, the space is typed first and the rest of this press is ignored.
    val press = order?.press { onTap() }
    fun stillOurs(): Boolean = press == null || order.finish(press)
    val thresholdPx = SpaceCursorSlideThreshold.toPx()
    var sliding = false
    var typedEarly = false
    var accumulated = 0f
    val holdTimeout = viewConfiguration.longPressTimeoutMillis
    val rungPx = SmartSelectRungDistance.toPx()
    var holding: SpaceHoldCallbacks? = null
    var holdStartY = 0f
    val flickPx = SmartSelectFlickDistance.toPx()
    var orderDone = false
    while (true) {
        val waitForHold = hold != null && holding == null && !sliding && !typedEarly && !orderDone &&
            press?.committedEarly != true
        val remaining = holdTimeout - (currentEvent.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis) + down.uptimeMillis
        val event = if (waitForHold) {
            withTimeoutOrNull(remaining.coerceAtLeast(0L)) { awaitPointerEvent() }
        } else awaitPointerEvent()
        if (event == null) {
            // LAB-011: the thumb rested on space without sliding: start smart select.
            val callbacks = hold?.invoke()
            if (stillOurs()) {
                orderDone = true
                if (callbacks != null && callbacks.onHold()) {
                    holding = callbacks
                    accumulated = 0f
                    holdStartY = currentEvent.changes.firstOrNull { it.id == down.id }?.position?.y ?: down.position.y
                }
            } else {
                typedEarly = true
            }
            continue
        }
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null) {
            if (holding != null) holding.onHoldEnd(false)
            else if (!sliding && !orderDone) stillOurs()
            return@awaitEachGesture
        }
        holding?.let { ladder ->
            if (!change.pressed) {
                change.consume()
                ladder.onHoldEnd(holdStartY - change.position.y >= flickPx)
                return@awaitEachGesture
            }
            // Read the travel before consuming: a consumed change reports zero.
            accumulated += change.positionChange().x
            change.consume()
            val rungs = (accumulated / rungPx).toInt()
            if (rungs != 0) {
                ladder.onRung(rungs)
                accumulated -= rungs * rungPx
            }
            continue
        }
        if (!change.pressed) {
            change.consume()
            if (!sliding && !typedEarly && (orderDone || stillOurs())) onTap()
            return@awaitEachGesture
        }
        if (typedEarly || (press?.committedEarly == true && !sliding)) {
            typedEarly = true
            change.consume()
            continue
        }
        if (sliding) {
            accumulated += change.positionChange().x
        } else {
            val travel = change.position.x - down.position.x
            if (abs(travel) >= thresholdPx) {
                if (!orderDone && !stillOurs()) {
                    typedEarly = true
                    change.consume()
                    continue
                }
                sliding = true
                accumulated = travel - sign(travel) * thresholdPx
            }
        }
        change.consume()
        if (sliding) {
            val steps = (accumulated / SPACE_CURSOR_STEP_PX).toInt()
            if (steps != 0) {
                onSlide(steps)
                accumulated -= steps * SPACE_CURSOR_STEP_PX
            }
        }
    }
}
