package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.*
import org.junit.Test

class TouchCalibrationTest {
    private val keys = mapOf('q' to TouchBounds(0f, 0f, 100f, 100f), 'w' to TouchBounds(100f, 0f, 100f, 100f))

    @Test fun `three guided samples remap an edge tap but keep central taps stable`() {
        val model = TouchCalibration()
        assertEquals('w', model.resolve('w', 110f, 50f, keys))
        repeat(3) {
            assertTrue(model.learn('q', 85f, 50f, keys.getValue('q')))
            assertTrue(model.learn('w', 185f, 50f, keys.getValue('w')))
        }
        assertEquals('q', model.resolve('w', 110f, 50f, keys))
        assertEquals('w', model.resolve('w', 150f, 50f, keys))
        assertEquals('q', TouchCalibration(model.encode()).resolve('w', 110f, 50f, keys))
        model.clear()
        assertEquals('w', model.resolve('w', 110f, 50f, keys))
    }

    @Test fun `normalized calibration follows resized keys`() {
        val model = TouchCalibration()
        repeat(3) {
            model.learn('q', 85f, 50f, keys.getValue('q'))
            model.learn('w', 185f, 50f, keys.getValue('w'))
        }
        val resized = mapOf('q' to TouchBounds(30f, 200f, 50f, 60f), 'w' to TouchBounds(80f, 200f, 50f, 60f))
        assertEquals('q', model.resolve('w', 85f, 230f, resized))
    }

    @Test fun `accidental distant taps and corrupt stored offsets are ignored`() {
        val model = TouchCalibration("q,NaN,0,3;w,99,0,3")
        assertEquals("", model.encode())
        assertFalse(model.learn('q', 500f, 50f, keys.getValue('q')))
        assertFalse(model.learn('q', Float.NaN, 50f, keys.getValue('q')))
        assertEquals("", model.encode())
    }
}
