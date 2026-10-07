package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardRetentionDefaultTest {

    private val now = 10 * ClipboardHistoryLimits.DAY_MS

    @Test
    fun `default retention is one day, the shortest existing option`() {
        assertEquals(1, ClipboardHistoryLimits.DEFAULT_RETENTION_DAYS)
        assertEquals(ClipboardHistoryLimits.MIN_RETENTION_DAYS, ClipboardHistoryLimits.DEFAULT_RETENTION_DAYS)
    }

    @Test
    fun `default cutoff expires unpinned clips older than one day and keeps pins`() {
        val records = listOf(
            ClipboardRetentionRecord(id = 1, pinned = false, lastSeenAt = now - ClipboardHistoryLimits.DAY_MS - 1),
            ClipboardRetentionRecord(id = 2, pinned = false, lastSeenAt = now - ClipboardHistoryLimits.DAY_MS + 1),
            ClipboardRetentionRecord(id = 3, pinned = true, lastSeenAt = now - 5 * ClipboardHistoryLimits.DAY_MS)
        )
        val plan = ClipboardHistoryPruner.plan(records, now, ClipboardHistoryLimits.DEFAULT_RETENTION_DAYS)
        assertEquals(setOf(1), plan.expiredIds)
        assertEquals(setOf(1), plan.idsToDelete)
    }

    @Test
    fun `an explicit seven day choice is still honored`() {
        val records = listOf(
            ClipboardRetentionRecord(id = 1, pinned = false, lastSeenAt = now - 3 * ClipboardHistoryLimits.DAY_MS)
        )
        assertEquals(emptySet<Int>(), ClipboardHistoryPruner.plan(records, now, retentionDays = 7).expiredIds)
    }
}
