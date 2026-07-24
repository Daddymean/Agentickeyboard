package io.github.daddymean.agentickeyboard.util.mastery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardMasteryInsightsTest {

    @Test
    fun aggregateHistoryIsBoundedToTwentyEightDays() {
        var state = MasteryState.fresh()
        for (day in 1L..35L) {
            state = KeyboardMastery.record(
                state,
                MasteryEvent.AUTO_CORRECTION,
                epochDay = day
            ).state
        }

        assertEquals(KeyboardMastery.HISTORY_DAYS, state.recentDays.size)
        assertEquals(8L, state.recentDays.first().epochDay)
        assertEquals(35L, state.recentDays.last().epochDay)
    }

    @Test
    fun legacyCodecMigratesPhaseAProgressAndCurrentDay() {
        val legacy = listOf(
            "v=1",
            "enabled=1",
            "path=FLOW:5",
            "events=SWIPE_WORD:2",
            "day=12",
            "dailyPath=FLOW:4",
            "dailyEvents=SWIPE_WORD:2",
            "current=2",
            "best=2",
            "last=12",
            "grace=1",
            "ach=first_flight"
        ).joinToString(";")

        val migrated = MasteryStateCodec.decode(legacy)

        assertEquals(5, migrated.xpFor(MasteryPath.FLOW))
        assertEquals(2, migrated.snapshotFor(12)?.count(MasteryEvent.SWIPE_WORD))
        assertTrue("first_flight" in migrated.achievements)
    }

    @Test
    fun codecRoundTripIncludesHistoryAndMissionDismissals() {
        var state = MasteryState.fresh()
        state = KeyboardMastery.record(state, MasteryEvent.SWIPE_WORD, 100).state
        state = KeyboardMasteryMissions.dismiss(state, "flow_shortcut_once", 100)

        val restored = MasteryStateCodec.decode(MasteryStateCodec.encode(state))

        assertEquals(state, restored)
    }

    @Test
    fun missionsFavorUnusedFeaturesAndDifferentPaths() {
        var state = MasteryState.fresh()
        repeat(12) {
            state = KeyboardMastery.record(state, MasteryEvent.SWIPE_WORD, 90).state
            state = KeyboardMastery.record(state, MasteryEvent.AUTO_CORRECTION, 90).state
        }

        val missions = KeyboardMasteryMissions.forDay(state, epochDay = 100)
        val paths = missions.map { it.definition.path }.toSet()

        assertEquals(3, missions.size)
        assertEquals(3, paths.size)
        assertTrue(missions.any { it.definition.path == MasteryPath.TRUST })
        assertTrue(missions.any { it.definition.path == MasteryPath.VOICE })
    }

    @Test
    fun missionProgressUsesOnlyTheCurrentDay() {
        var state = MasteryState.fresh()
        repeat(4) {
            state = KeyboardMastery.record(state, MasteryEvent.SWIPE_WORD, 99).state
        }
        repeat(3) {
            state = KeyboardMastery.record(state, MasteryEvent.SWIPE_WORD, 100).state
        }

        val mission = KeyboardMasteryMissions.catalog
            .first { it.id == "flow_swipe_five" }
        val visible = KeyboardMasteryMissions.forDay(state, epochDay = 100, limit = 8)
            .first { it.definition.id == mission.id }

        assertEquals(3, visible.progress)
        assertFalse(visible.completed)
    }

    @Test
    fun dismissedMissionReturnsOnTheNextDayWithoutPenalty() {
        val missionId = "flow_shortcut_once"
        val dismissed = KeyboardMasteryMissions.dismiss(
            MasteryState.fresh(),
            missionId,
            epochDay = 100
        )

        assertFalse(
            KeyboardMasteryMissions.forDay(dismissed, 100, limit = 8)
                .any { it.definition.id == missionId }
        )
        assertTrue(
            KeyboardMasteryMissions.forDay(dismissed, 101, limit = 8)
                .any { it.definition.id == missionId }
        )
        assertEquals(0, dismissed.totalXp)
    }

    @Test
    fun disabledMasteryHasNoMissions() {
        assertTrue(
            KeyboardMasteryMissions.forDay(
                MasteryState.fresh(enabled = false),
                epochDay = 100
            ).isEmpty()
        )
    }

    @Test
    fun weeklyReportComparesRollingSevenDayWindows() {
        var state = MasteryState.fresh()
        repeat(2) {
            state = KeyboardMastery.record(
                state,
                MasteryEvent.SHORTCUT_EXPANSION,
                epochDay = 94
            ).state
        }
        repeat(3) {
            state = KeyboardMastery.record(
                state,
                MasteryEvent.AUTO_CORRECTION,
                epochDay = 101
            ).state
        }
        state = KeyboardMastery.record(state, MasteryEvent.AI_APPLY, 107).state

        val report = KeyboardMasteryReports.rollingWeek(state, epochDay = 107)

        assertEquals(4, report.totalActions)
        assertEquals(2, report.previousTotalActions)
        assertEquals(2, report.activeDays)
        assertEquals(1, report.previousActiveDays)
        assertEquals(36, report.estimatedKeystrokesSaved)
        assertEquals(MasteryPath.CLARITY, report.dominantPath)
        assertEquals(3, report.bestDayActions)
        assertEquals(2, report.actionDelta)
    }

    @Test
    fun missionCatalogNeverTargetsAndroidDefaultSelection() {
        val text = KeyboardMasteryMissions.catalog.joinToString(" ") {
            "${it.id} ${it.title} ${it.description}"
        }.lowercase()

        assertFalse("default keyboard" in text)
        assertFalse("input method" in text)
        assertFalse("settings" in text)
    }
}
