package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/*
 * KEYBOARD-018: overlapping presses type in the order the keys were pressed.
 *
 * Keys commit on release, so with two thumbs overlapping the output used to
 * follow the order the fingers lifted ("ai" came out "ia"). Now, when any key
 * goes down while a letter is still held, the held letter is committed at once
 * and its own release adds nothing.
 */
class KeyPressOrder {
    /** One press that has not been typed yet. */
    class Press internal constructor(internal val commit: () -> Unit) {
        /** True once the press was typed early because another key went down. */
        var committedEarly: Boolean = false
            internal set
    }

    private val pending = ArrayList<Press>()

    /** Types every held press now, oldest first. Called when any key goes down. */
    fun flush() {
        if (pending.isEmpty()) return
        val held = pending.toList()
        pending.clear()
        for (press in held) {
            press.committedEarly = true
            press.commit()
        }
    }

    /**
     * A key went down: earlier held presses are typed first, then this one is
     * held. [commitEarly] types it if another key goes down before it is released.
     */
    fun press(commitEarly: () -> Unit): Press {
        flush()
        return Press(commitEarly).also { pending.add(it) }
    }

    /**
     * The press ended (released, cancelled or turned into a hold or slide).
     * Returns true if it still has to be typed by its owner.
     */
    fun finish(press: Press): Boolean {
        pending.remove(press)
        return !press.committedEarly
    }
}

/** The keyboard's shared press order; null outside a keyboard (previews, tests of single keys). */
val LocalKeyPressOrder = staticCompositionLocalOf<KeyPressOrder?> { null }

/**
 * For keys that do not take part in ordering themselves (shift, backspace, enter,
 * mode keys): pressing them first types any held letter, so a held letter can
 * never land after them. Observes only; consumes nothing.
 */
internal fun Modifier.flushHeldKeysOnPress(order: KeyPressOrder?): Modifier =
    if (order == null) this else pointerInput(order) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            order.flush()
        }
    }
