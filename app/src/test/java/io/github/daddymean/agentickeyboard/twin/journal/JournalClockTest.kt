package io.github.daddymean.agentickeyboard.twin.journal

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** KEYBOARD-025 row 5: days are the phone's local days, across midnight and DST. */
class JournalClockTest {
    private val pt = ZoneId.of("America/Los_Angeles")

    @Test
    fun answersBelongToTheLocalDay() {
        val lateEvening = ZonedDateTime.of(2026, 10, 12, 23, 59, 0, 0, pt).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 10, 12), JournalHistory.date(lateEvening, pt))
        assertEquals(LocalDate.of(2026, 10, 13), JournalHistory.date(lateEvening, ZoneId.of("UTC")))
        val afterMidnight = lateEvening + 2 * 60_000
        assertEquals(LocalDate.of(2026, 10, 13), JournalHistory.date(afterMidnight, pt))
    }

    @Test
    fun dstChangeDoesNotShiftDays() {
        // 2026-11-01 01:30 happens twice in PT; both are Nov 1.
        val first = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, pt).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val second = ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, pt).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 1), JournalHistory.date(first, pt))
        assertEquals(LocalDate.of(2026, 11, 1), JournalHistory.date(second, pt))
        val spring = ZonedDateTime.of(2027, 3, 14, 3, 30, 0, 0, pt).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2027, 3, 14), JournalHistory.date(spring, pt))
    }

    @Test
    fun weekCountingIgnoresTimeOfDay() {
        val s = JournalState(startDate = LocalDate.of(2026, 10, 12))
        assertEquals(1, JournalScheduler.week(s, LocalDate.of(2026, 10, 18)))
        assertEquals(2, JournalScheduler.week(s, LocalDate.of(2026, 10, 19)))
        assertEquals(1, JournalScheduler.week(s, LocalDate.of(2026, 10, 1)))
    }
}
