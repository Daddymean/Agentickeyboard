package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLabelCacheTest {

    private var now = 0L
    private val lookups = mutableListOf<String>()
    private fun cache(max: Int = 32, ttl: Long = 600_000L) = AppLabelCache(max, ttl) { now }
    private val pm: (String) -> String = { pkg -> lookups += pkg; if (pkg == "hidden") "" else pkg.uppercase() }

    @Test
    fun repeatFocus_resolvesOnce() {
        val c = cache()
        repeat(5) { assertEquals("CHAT", c.get("chat", pm)) }
        assertEquals(listOf("chat"), lookups)
    }

    @Test
    fun blankAndNull_areNotLookedUpOrCached() {
        val c = cache()
        assertEquals("", c.get(null, pm))
        assertEquals("", c.get(" ", pm))
        assertEquals("", c.get("hidden", pm))
        assertEquals("", c.get("hidden", pm))
        assertEquals(listOf("hidden", "hidden"), lookups)
    }

    @Test
    fun expiredEntry_isResolvedAgain() {
        val c = cache(ttl = 1_000)
        c.get("chat", pm)
        now = 999
        c.get("chat", pm)
        now = 1_000
        c.get("chat", pm)
        assertEquals(listOf("chat", "chat"), lookups)
    }

    @Test
    fun leastRecentlyUsed_isEvicted() {
        val c = cache(max = 2)
        c.get("a", pm); c.get("b", pm); c.get("a", pm); c.get("c", pm) // evicts b
        c.get("a", pm); c.get("b", pm)
        assertEquals(listOf("a", "b", "c", "b"), lookups)
    }

    @Test
    fun clear_forgetsEverything() {
        val c = cache()
        c.get("chat", pm); c.clear(); c.get("chat", pm)
        assertEquals(listOf("chat", "chat"), lookups)
    }

    /**
     * LAB-003 measurement: PackageManager binder calls (getApplicationInfo +
     * getApplicationLabel = 2 per lookup) over a session of 60 field focuses
     * across 4 apps, all inside the 10-minute TTL. Counts, not time.
     */
    @Test
    fun focusSession_packageManagerCalls() {
        val apps = listOf("messages", "chat", "mail", "browser")
        val focuses = (0 until 60).map { apps[(it * 7 + it / 5) % apps.size] }
        val c = cache()
        focuses.forEach { c.get(it, pm) }
        val before = 2 * focuses.size
        val after = 2 * lookups.size
        println("LAB-003 PackageManager calls for ${focuses.size} focuses over ${apps.size} apps: before=$before after=$after")
        assertEquals(120, before)
        assertEquals(8, after)
    }
}
