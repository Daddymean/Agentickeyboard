package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** LAB-013: on-device selection transforms. */
class SelectionTransformsTest {
    private val la = ZoneId.of("America/Los_Angeles")
    private val ny = ZoneId.of("America/New_York")
    private val day = LocalDate.of(2026, 11, 17)

    private fun outputs(s: String, zone: ZoneId = la) = SelectionTransforms.suggest(s, zone, day, Locale.US).map { it.output }

    @Test fun convertsUnits() {
        assertEquals("7.77 mi", SelectionTransforms.convertUnit("12.5 km")?.output)
        assertEquals("21.1°C", SelectionTransforms.convertUnit("70°F")?.output)
        assertEquals("2.27 kg", SelectionTransforms.convertUnit("5 lb")?.output)
        assertEquals("1609 km", SelectionTransforms.convertUnit("1,000 miles")?.output)
        assertNull(SelectionTransforms.convertUnit("12 apples"))
        assertTrue(outputs("12.5 km").first() == "7.77 mi")
    }

    @Test fun convertsTimeZonesToThePhonesZone() {
        assertEquals(listOf("6:00 PM ET"), SelectionTransforms.convertTime("3pm PT", ny, day).map { it.output })
        assertEquals(listOf("12:30 PM PT"), SelectionTransforms.convertTime("3:30 pm ET", la, day).map { it.output })
        assertEquals(listOf("7:00 PM PT (−1d)"), SelectionTransforms.convertTime("3am UTC", la, day).map { it.output })
        // Already in the phone's zone: offer ET and UTC instead.
        assertEquals(listOf("6:00 PM ET", "11:00 PM UTC"), SelectionTransforms.convertTime("3pm PT", la, day).map { it.output })
        assertTrue(SelectionTransforms.convertTime("12 CT", la, day).isEmpty())
        assertTrue(SelectionTransforms.convertTime("13pm PT", la, day).isEmpty())
    }

    @Test fun evaluatesArithmetic() {
        assertEquals("57", SelectionTransforms.evaluate("12*4.5+3"))
        assertEquals("7", SelectionTransforms.evaluate("(2 + 5) × 1"))
        assertEquals("2.5", SelectionTransforms.evaluate("10 ÷ 4"))
        assertEquals("1024", SelectionTransforms.evaluate("2^10"))
        assertEquals("1,250".let { null }, SelectionTransforms.evaluate("1,250"))
        assertNull(SelectionTransforms.evaluate("555-123-4567".replace("-", "")))
        assertNull(SelectionTransforms.evaluate("hello + world"))
        assertNull(SelectionTransforms.evaluate("1 / 0"))
    }

    @Test fun listsLinesWrapAndCase() {
        assertEquals("• eggs\n• milk\n• bread", SelectionTransforms.bulletList("eggs, milk, and bread"))
        assertNull(SelectionTransforms.bulletList("just, two"))
        assertEquals("apple\nBanana\ncherry", SelectionTransforms.sortLines("cherry\napple\nBanana"))
        val o = outputs("hello world")
        assertTrue(o.contains("“hello world”"))
        assertTrue(o.contains("Hello World"))
        assertTrue(o.contains("HELLO WORLD"))
        assertTrue(!o.contains("hello world"))
    }

    @Test fun caps() {
        assertTrue(outputs("").isEmpty())
        assertTrue(outputs("x".repeat(SelectionTransforms.MAX_CHARS + 1)).isEmpty())
        assertTrue(outputs("a b c d e f").size <= SelectionTransforms.MAX_CHIPS)
    }

    @Test fun fastEnoughToRunOnAFlick() {
        repeat(200) { SelectionTransforms.suggest("3:30 pm ET", la, day, Locale.US) }
        val t0 = System.nanoTime()
        val n = 500
        repeat(n) { SelectionTransforms.suggest(if (it % 2 == 0) "12*4.5+3" else "eggs, milk, and bread", la, day, Locale.US) }
        val us = (System.nanoTime() - t0) / 1000.0 / n
        println("SelectionTransforms.suggest: %.1f µs/call".format(us))
        assertTrue(us < 16_000)
    }
}
