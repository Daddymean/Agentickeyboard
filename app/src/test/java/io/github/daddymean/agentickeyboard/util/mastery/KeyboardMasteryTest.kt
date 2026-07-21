package io.github.daddymean.agentickeyboard.util.mastery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardMasteryTest {

    @Test
    fun repeatedEventCannotGenerateUnlimitedXp() {
        var state = MasteryState.fresh()
        repeat(30) {
            state = KeyboardMastery.record(state, MasteryEvent.SWIPE_WORD, epochDay = 100).state
        }

        assertEquals(20, state.xpFor(MasteryPath.FLOW))
        assertEquals(30, state.eventCounts[MasteryEvent.SWIPE_WORD])
    }

    @Test
    fun pathXpIsCappedPerDayAcrossEvents() {
        var state = MasteryState.fresh()
        repeat(20) {
            state = KeyboardMastery.record(state, MasteryEvent.SHORTCUT_EXPANSION, epochDay = 100).state
        }

        assertEquals(KeyboardMastery.DAILY_PATH_XP_CAP, state.xpFor(MasteryPath.FLOW))
        assertEquals(KeyboardMastery.DAILY_PATH_XP_CAP, state.dailyPathXp[MasteryPath.FLOW])
    }

    @Test
    fun nextDayAdvancesStreak() {
        val first = KeyboardMastery.record(
            MasteryState.fresh(), MasteryEvent.AI_APPLY, epochDay = 10
        ).state
        val second = KeyboardMastery.record(
            first, MasteryEvent.AUTO_CORRECTION, epochDay = 11
        ).state

        assertEquals(2, second.currentStreak)
        assertEquals(2, second.bestStreak)
    }

    @Test
    fun oneMissedDayUsesGraceWithoutBreakingRhythm() {
        val first = KeyboardMastery.record(
            MasteryState.fresh(), MasteryEvent.AI_APPLY, epochDay = 10
        ).state
        val second = KeyboardMastery.record(
            first, MasteryEvent.AUTO_CORRECTION, epochDay = 12
        ).state

        assertEquals(2, second.currentStreak)
        assertEquals(0, second.graceDays)
    }

    @Test
    fun longerGapResetsCurrentButKeepsPersonalBest() {
        var state = MasteryState.fresh()
        state = KeyboardMastery.record(state, MasteryEvent.AI_APPLY, 10).state
        state = KeyboardMastery.record(state, MasteryEvent.AI_APPLY, 11).state
        state = KeyboardMastery.record(state, MasteryEvent.AI_APPLY, 12).state
        state = KeyboardMastery.record(state, MasteryEvent.AI_APPLY, 20).state

        assertEquals(1, state.currentStreak)
        assertEquals(3, state.bestStreak)
    }

    @Test
    fun sensitiveFieldsNeverProduceProgress() {
        val original = MasteryState.fresh()
        val award = KeyboardMastery.record(
            original,
            MasteryEvent.SHORTCUT_EXPANSION,
            epochDay = 42,
            isSensitiveField = true
        )

        assertEquals(original, award.state)
        assertEquals(0, award.xpAwarded)
        assertFalse(award.streakAdvanced)
    }

    @Test
    fun disabledMasteryNeverProducesProgress() {
        val original = MasteryState.fresh(enabled = false)
        val award = KeyboardMastery.record(original, MasteryEvent.AI_APPLY, epochDay = 42)

        assertEquals(original, award.state)
        assertEquals(0, award.xpAwarded)
    }

    @Test
    fun achievementsUnlockFromAggregateEvents() {
        var state = MasteryState.fresh()
        repeat(10) {
            state = KeyboardMastery.record(state, MasteryEvent.AUTO_CORRECTION, epochDay = 5).state
        }

        assertTrue("clear_signal" in state.achievements)
    }

    @Test
    fun codecRoundTripsAllProgress() {
        var state = MasteryState.fresh()
        state = KeyboardMastery.record(state, MasteryEvent.SWIPE_WORD, 100).state
        state = KeyboardMastery.record(state, MasteryEvent.OFFLINE_AI_APPLY, 101).state

        val restored = MasteryStateCodec.decode(MasteryStateCodec.encode(state))

        assertEquals(state, restored)
    }

    @Test
    fun malformedPersistenceFallsBackSafely() {
        val restored = MasteryStateCodec.decode("not-valid", enabledFallback = false)

        assertEquals(MasteryState.fresh(enabled = false), restored)
    }
}
