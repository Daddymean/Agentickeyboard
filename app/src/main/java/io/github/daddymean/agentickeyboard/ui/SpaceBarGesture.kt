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
    onSlide: (Int) -> Unit
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
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null) {
            if (!sliding) stillOurs()
            return@awaitEachGesture
        }
        if (!change.pressed) {
            change.consume()
            if (!sliding && !typedEarly && stillOurs()) onTap()
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
                if (!stillOurs()) {
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
