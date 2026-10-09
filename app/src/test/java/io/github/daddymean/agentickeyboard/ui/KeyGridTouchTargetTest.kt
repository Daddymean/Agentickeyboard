package io.github.daddymean.agentickeyboard.ui

import org.junit.Assert.assertTrue
import org.junit.Test

/** KEYBOARD-015: the grid target must cover the row gap and the middle-row indent. */
class KeyGridTouchTargetTest {
    @Test
    fun `target covers the row gap and middle-row indent at common widths and key sizes`() {
        for (width in listOf(320, 360, 393, 411, 480, 600)) for (height in listOf(400, 640, 891)) {
            for (scale in listOf(1.0f, 1.15f, 1.3f)) {
                val m = keyboardMetricsFor(width, height, scale)
                val target = keyGridTouchTarget(m)
                val verticalReach = (target.height - m.keyHeight).value / 2
                assertTrue("vertical reach $verticalReach must exceed row gap ${m.rowGap} ($width×$height@$scale)",
                    verticalReach > m.rowGap.value)
                // Nine keys plus a trailing gap after each, centred in the screen width.
                val middleRow = 9 * m.keyWidth.value + 9 * m.keyGap.value
                val indent = (width - middleRow) / 2 + m.keyGap.value
                val horizontalReach = (target.width - m.keyWidth).value / 2
                if (width - middleRow < 4 * m.keyWidth.value) {
                    assertTrue("horizontal reach $horizontalReach must cover indent $indent ($width×$height@$scale)",
                        horizontalReach >= indent)
                }
            }
        }
    }
}
