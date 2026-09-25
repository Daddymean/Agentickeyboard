package io.github.daddymean.agentickeyboard.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyboard has to share the screen with the app being typed into. These
 * tests pin the two invariants that make that true: a QWERTY row never exceeds
 * the window width, and a short window (a phone in landscape) gets a keyboard
 * that still leaves the text field visible.
 */
class KeyboardMetricsTest {

    /** Widest row: 10 keys with a gap between each. */
    private fun rowWidthDp(m: KeyboardMetrics): Float =
        m.keyWidth.value * 10 + m.keyGap.value * 9

    /** Shelf + four key rows + bottom padding, i.e. the keyboard minus banners. */
    private fun coreHeightDp(m: KeyboardMetrics, rows: Int = 4): Float =
        m.shelfHeight.value + rows * (m.keyHeight.value + m.rowGap.value * 2) + m.bottomPadding.value

    @Test
    fun `portrait phone keeps the established key size`() {
        val m = keyboardMetricsFor(screenWidthDp = 360, screenHeightDp = 800)
        assertFalse(m.isCompact)
        assertEquals(32.dp, m.keyWidth)
        assertEquals(44.dp, m.keyHeight)
        assertEquals(64.dp, m.shelfHeight)
    }

    @Test
    fun `a qwerty row fits inside every realistic phone width`() {
        // 320dp is the narrowest width any supported Android phone reports.
        for (width in 320..480) {
            val m = keyboardMetricsFor(screenWidthDp = width, screenHeightDp = 800)
            assertTrue(
                "row of 10 keys overflows a ${width}dp window: ${rowWidthDp(m)}dp",
                rowWidthDp(m) <= width
            )
        }
    }

    @Test
    fun `keys grow with the window instead of staying pinned at 32dp`() {
        val small = keyboardMetricsFor(screenWidthDp = 360, screenHeightDp = 800)
        val large = keyboardMetricsFor(screenWidthDp = 412, screenHeightDp = 900)
        assertTrue(large.keyWidth > small.keyWidth)
    }

    @Test
    fun `key width is clamped so it stays a usable touch target and never absurd`() {
        val tiny = keyboardMetricsFor(screenWidthDp = 200, screenHeightDp = 800)
        assertEquals(28.dp, tiny.keyWidth)
        val huge = keyboardMetricsFor(screenWidthDp = 2000, screenHeightDp = 1200)
        assertEquals(64.dp, huge.keyWidth)
    }

    @Test
    fun `a phone in landscape gets a compact keyboard that leaves room for the field`() {
        val m = keyboardMetricsFor(screenWidthDp = 780, screenHeightDp = 360)
        assertTrue(m.isCompact)
        // Before adaptive metrics this came to ~272dp of a 360dp-tall window,
        // which clipped the keyboard and hid the field entirely.
        assertTrue(
            "compact keyboard is still too tall: ${coreHeightDp(m)}dp",
            coreHeightDp(m) <= 190f
        )
    }

    @Test
    fun `a tablet in landscape is not treated as compact`() {
        val m = keyboardMetricsFor(screenWidthDp = 1280, screenHeightDp = 800)
        assertFalse(m.isCompact)
        assertEquals(44.dp, m.keyHeight)
    }

    @Test
    fun `special keys keep their proportions relative to the base key`() {
        val m = keyboardMetricsFor(screenWidthDp = 360, screenHeightDp = 800)
        assertEquals(44.dp, m.wideKeyWidth)       // shift, backspace
        assertEquals(56.dp, m.extraWideKeyWidth)  // ?123, Enter
        assertEquals(40.dp, m.mediumKeyWidth)     // privacy, voice
        assertEquals(36.dp, m.smallKeyWidth)      // punctuation
    }
}
