package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric for org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HaggleAdvisorTest {
    private val cache = """{"version":1,"generated":"2026-11-02","items":[
        {"key":"nintendo switch","aliases":["switch"],"median_sold_cents":15000,"n":30},
        {"key":"switch oled","median_sold_cents":21000,"n":12},
        {"key":"rare lamp","median_sold_cents":9000,"n":2}]}"""
    private val comps get() = HaggleAdvisor.parseCache(cache)

    @Test fun parsesCacheAndRejectsBadInput() {
        assertEquals(3, comps.size)
        assertTrue(HaggleAdvisor.parseCache("not json").isEmpty())
        assertTrue(HaggleAdvisor.parseCache("""{"version":2,"items":[]}""").isEmpty())
    }

    @Test fun parsesAskingPrice() {
        assertEquals(25_000L, HaggleAdvisor.parseAskingCents("is the switch oled still $250?"))
        assertEquals(129_999L, HaggleAdvisor.parseAskingCents("$1,299.99 firm"))
        assertNull(HaggleAdvisor.parseAskingCents("how much?"))
    }

    @Test fun longestMatchWins() {
        assertEquals("switch oled", HaggleAdvisor.match("would you take less for the Switch OLED", comps)!!.key)
        assertEquals("nintendo switch", HaggleAdvisor.match("the switch", comps)!!.key)
    }

    @Test fun countersStayUnderAskAndMedian() {
        val a = HaggleAdvisor.advise("Hi, is the switch oled $250? ", comps)!!
        assertEquals(listOf(15_800L, 18_900L, 21_000L), a.counters)
        assertTrue(a.summary.contains("above Flip median $210"))
    }

    @Test fun cheapAskGetsLowerCountersOnly() {
        val a = HaggleAdvisor.advise("switch for $100", comps)!!
        assertTrue(a.counters.all { it < 10_000 })
    }

    @Test fun thinSampleOrNoPriceGivesNothing() {
        assertNull(HaggleAdvisor.advise("rare lamp $80", comps))
        assertNull(HaggleAdvisor.advise("is the switch available", comps))
        assertNull(HaggleAdvisor.advise("bike $50", comps))
    }
}
