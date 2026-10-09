package io.github.daddymean.agentickeyboard.util

import dev.context.core.model.Episode
import dev.context.core.model.NextEvent
import dev.context.core.model.Sensitivity
import dev.context.core.model.Snapshot
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextNotesTest {

    private val berlin = ZoneId.of("Europe/Berlin")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    // 2026-10-09 08:00 in Berlin (06:00 UTC).
    private val now = ZonedDateTime.of(2026, 10, 9, 8, 0, 0, 0, berlin).toInstant().toEpochMilli()

    private fun at(hour: Int, minute: Int, day: Int = 9, zone: ZoneId = berlin): Long =
        ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun episode(title: String) = Episode(
        id = "e1", startMs = 0L, endMs = 1L, kind = "focus", title = title,
        summary = "", eventIds = "[]", sensitivity = Sensitivity.PRIVATE
    )

    @Test
    fun noteSensitivityFollowsSyncSetting() {
        assertEquals(Sensitivity.PERSONAL, ContextNotes.noteSensitivity(syncToCloud = true))
        assertEquals(Sensitivity.PRIVATE, ContextNotes.noteSensitivity(syncToCloud = false))
        assertTrue(Sensitivity.isSyncable(ContextNotes.noteSensitivity(true)))
        assertFalse(Sensitivity.isSyncable(ContextNotes.noteSensitivity(false)))
    }

    @Test
    fun blankNotesAreIgnoredAndTextIsTrimmed() {
        assertNull(ContextNotes.noteTextOrNull(""))
        assertNull(ContextNotes.noteTextOrNull("   \n\t "))
        assertEquals("buy milk", ContextNotes.noteTextOrNull("  buy milk \n"))
    }

    @Test
    fun noSnapshotOrNothingKnownHidesChip() {
        assertNull(ContextNotes.chipText(null, now, berlin, Locale.US))
        assertNull(ContextNotes.chipText(Snapshot.empty(now), now, berlin, Locale.US))
    }

    @Test
    fun nextEventTodayShowsTwentyFourHourTimeInGivenZone() {
        val snapshot = Snapshot(generatedAtMs = now, nextEvent = NextEvent("Dentist", at(14, 5)))
        assertEquals("Next: Dentist 14:05", ContextNotes.chipText(snapshot, now, berlin, Locale.US))
        // Same instant rendered in another zone is still "today" there (21:05 JST).
        assertEquals("Next: Dentist 21:05", ContextNotes.chipText(snapshot, now, tokyo, Locale.US))
    }

    @Test
    fun nextEventOnAnotherDayIncludesWeekday() {
        val snapshot = Snapshot(generatedAtMs = now, nextEvent = NextEvent("Standup", at(9, 30, day = 10)))
        assertEquals("Next: Standup Sat 09:30", ContextNotes.chipText(snapshot, now, berlin, Locale.US))
    }

    @Test
    fun pastEventFallsBackToActiveEpisode() {
        val snapshot = Snapshot(
            generatedAtMs = now,
            activeEpisode = episode("Deep work"),
            nextEvent = NextEvent("Already started", at(7, 0))
        )
        assertEquals("Deep work", ContextNotes.chipText(snapshot, now, berlin, Locale.US))
    }

    @Test
    fun activeEpisodeShownWhenNoNextEvent() {
        val snapshot = Snapshot(generatedAtMs = now, activeEpisode = episode("Commute"))
        assertEquals("Commute", ContextNotes.chipText(snapshot, now, berlin, Locale.US))
        val blank = Snapshot(generatedAtMs = now, activeEpisode = episode("  "))
        assertNull(ContextNotes.chipText(blank, now, berlin, Locale.US))
    }

    @Test
    fun longTitlesAreShortened() {
        val title = "A very long meeting title that keeps going and going"
        val snapshot = Snapshot(generatedAtMs = now, activeEpisode = episode(title))
        val chip = ContextNotes.chipText(snapshot, now, berlin, Locale.US)!!
        assertTrue(chip.endsWith("…"))
        assertEquals(ContextNotes.MAX_CHIP_TITLE_CHARS, chip.length)
    }
}
