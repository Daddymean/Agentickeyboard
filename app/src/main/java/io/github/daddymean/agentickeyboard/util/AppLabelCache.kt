package io.github.daddymean.agentickeyboard.util

/**
 * LAB-003: remembers app labels so onStartInput does not make PackageManager
 * binder calls (getApplicationInfo + getApplicationLabel) on the main thread
 * every time a field gains focus.
 *
 * Entries expire after [ttlMillis], so a renamed app or a locale change shows
 * up within that window. Blank results (unknown or hidden packages) are not
 * cached, so they are retried. The cache holds at most [maxEntries] packages,
 * dropping the least recently used.
 */
class AppLabelCache(
    private val maxEntries: Int = 32,
    private val ttlMillis: Long = 10 * 60 * 1000L,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private class Entry(val label: String, val storedAt: Long)

    private val entries = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > maxEntries
    }

    /** The label for [packageName], from the cache when fresh, otherwise from [resolve]. */
    @Synchronized
    fun get(packageName: String?, resolve: (String) -> String): String {
        if (packageName.isNullOrBlank()) return ""
        val now = clock()
        entries[packageName]?.let { if (now - it.storedAt < ttlMillis) return it.label }
        val label = resolve(packageName)
        if (label.isBlank()) entries.remove(packageName) else entries[packageName] = Entry(label, now)
        return label
    }

    @Synchronized
    fun clear() = entries.clear()
}
