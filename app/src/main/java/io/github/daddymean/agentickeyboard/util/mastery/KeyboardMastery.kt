package io.github.daddymean.agentickeyboard.util.mastery

import kotlin.math.min

/** The four skill paths surfaced by the local Keyboard Mastery system. */
enum class MasteryPath(val label: String, val symbol: String) {
    FLOW("Flow", "⚡"),
    CLARITY("Clarity", "◇"),
    VOICE("Voice", "✦"),
    TRUST("Trust", "◈")
}

/** Privacy-safe aggregate actions that can advance mastery. No event carries text. */
enum class MasteryEvent(val path: MasteryPath, val xp: Int) {
    AUTO_CORRECTION(MasteryPath.CLARITY, 4),
    SWIPE_WORD(MasteryPath.FLOW, 2),
    AI_APPLY(MasteryPath.CLARITY, 6),
    SHORTCUT_EXPANSION(MasteryPath.FLOW, 5),
    VOICE_LOCK_APPLY(MasteryPath.VOICE, 8),
    OFFLINE_AI_APPLY(MasteryPath.TRUST, 8),
    TRANSLATION_APPLY(MasteryPath.VOICE, 7),
    REFINEMENT(MasteryPath.VOICE, 5)
}

data class MasteryAchievement(
    val id: String,
    val title: String,
    val description: String,
    val symbol: String
)

object MasteryAchievements {
    val catalog = listOf(
        MasteryAchievement("first_flight", "First Flight", "Swipe your first word.", "🛫"),
        MasteryAchievement("time_locksmith", "Time Locksmith", "Expand your first shortcut.", "🔑"),
        MasteryAchievement("clear_signal", "Clear Signal", "Accept ten automatic corrections.", "📡"),
        MasteryAchievement("voice_found", "Voice Found", "Apply a voice-locked AI result.", "🎙"),
        MasteryAchievement("private_pilot", "Private Pilot", "Apply an offline AI result.", "🛡"),
        MasteryAchievement("polyglot_spark", "Polyglot Spark", "Apply your first translation.", "🌐"),
        MasteryAchievement("fine_tuned", "Fine Tuned", "Refine five AI results.", "🎚"),
        MasteryAchievement("steady_signal", "Steady Signal", "Build a seven-day rhythm.", "🔥")
    )

    fun byId(id: String): MasteryAchievement? = catalog.firstOrNull { it.id == id }
}

/** One bounded, aggregate-only day used by missions and the rolling weekly report. */
data class MasteryDaySnapshot(
    val epochDay: Long,
    val eventCounts: Map<MasteryEvent, Int> = emptyMap()
) {
    val totalEvents: Int get() = eventCounts.values.sum()

    fun count(event: MasteryEvent): Int = eventCounts[event] ?: 0
}

/**
 * Entirely local progression state. It contains only counters, dates, and IDs.
 * Raw typed content, app names, recipients, and field values never enter it.
 */
data class MasteryState(
    val enabled: Boolean = true,
    val pathXp: Map<MasteryPath, Int> = zeroPathMap(),
    val eventCounts: Map<MasteryEvent, Int> = emptyMap(),
    val dailyEpochDay: Long = NO_DAY,
    val dailyPathXp: Map<MasteryPath, Int> = zeroPathMap(),
    val dailyEventCounts: Map<MasteryEvent, Int> = emptyMap(),
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val lastActiveEpochDay: Long = NO_DAY,
    val graceDays: Int = 1,
    val achievements: Set<String> = emptySet(),
    val recentDays: List<MasteryDaySnapshot> = emptyList(),
    val missionEpochDay: Long = NO_DAY,
    val dismissedMissionIds: Set<String> = emptySet()
) {
    val totalXp: Int get() = pathXp.values.sum()

    fun xpFor(path: MasteryPath): Int = pathXp[path] ?: 0

    fun levelFor(path: MasteryPath): Int = 1 + xpFor(path) / XP_PER_LEVEL

    fun levelProgress(path: MasteryPath): Float =
        (xpFor(path) % XP_PER_LEVEL).toFloat() / XP_PER_LEVEL.toFloat()

    fun snapshotFor(epochDay: Long): MasteryDaySnapshot? =
        recentDays.firstOrNull { it.epochDay == epochDay }

    companion object {
        const val NO_DAY: Long = Long.MIN_VALUE
        const val XP_PER_LEVEL: Int = 100

        fun fresh(enabled: Boolean = true): MasteryState = MasteryState(enabled = enabled)

        internal fun zeroPathMap(): Map<MasteryPath, Int> =
            MasteryPath.values().associateWith { 0 }
    }
}

data class MasteryAward(
    val state: MasteryState,
    val xpAwarded: Int = 0,
    val unlockedAchievementIds: Set<String> = emptySet(),
    val streakAdvanced: Boolean = false
)

/** Deterministic rules for progression, anti-farming, achievements, and streaks. */
object KeyboardMastery {
    const val DAILY_PATH_XP_CAP = 40
    const val MAX_REWARDED_EVENT_REPETITIONS_PER_DAY = 10
    const val MAX_GRACE_DAYS = 2
    const val HISTORY_DAYS = 28

    fun record(
        state: MasteryState,
        event: MasteryEvent,
        epochDay: Long,
        isSensitiveField: Boolean = false
    ): MasteryAward {
        if (!state.enabled || isSensitiveField) return MasteryAward(state)

        val missionState = if (state.missionEpochDay == epochDay) {
            state
        } else {
            state.copy(missionEpochDay = epochDay, dismissedMissionIds = emptySet())
        }
        val dayState = if (missionState.dailyEpochDay == epochDay) {
            missionState
        } else {
            missionState.copy(
                dailyEpochDay = epochDay,
                dailyPathXp = MasteryState.zeroPathMap(),
                dailyEventCounts = emptyMap()
            )
        }

        val dailyEventCount = dayState.dailyEventCounts[event] ?: 0
        val dailyPathTotal = dayState.dailyPathXp[event.path] ?: 0
        val canRewardEvent = dailyEventCount < MAX_REWARDED_EVENT_REPETITIONS_PER_DAY
        val remainingPathXp = (DAILY_PATH_XP_CAP - dailyPathTotal).coerceAtLeast(0)
        val xpAwarded = if (canRewardEvent) min(event.xp, remainingPathXp) else 0

        val nextPathXp = dayState.pathXp.toMutableMap().apply {
            this[event.path] = (this[event.path] ?: 0) + xpAwarded
        }
        val nextDailyPathXp = dayState.dailyPathXp.toMutableMap().apply {
            this[event.path] = dailyPathTotal + xpAwarded
        }
        val nextEventCounts = dayState.eventCounts.toMutableMap().apply {
            this[event] = (this[event] ?: 0) + 1
        }
        val nextDailyEventCounts = dayState.dailyEventCounts.toMutableMap().apply {
            this[event] = dailyEventCount + 1
        }

        val streak = advanceStreak(dayState, epochDay)
        val candidate = dayState.copy(
            pathXp = nextPathXp,
            eventCounts = nextEventCounts,
            dailyPathXp = nextDailyPathXp,
            dailyEventCounts = nextDailyEventCounts,
            currentStreak = streak.current,
            bestStreak = streak.best,
            lastActiveEpochDay = epochDay,
            graceDays = streak.graceDays,
            recentDays = updateRecentDays(dayState.recentDays, event, epochDay)
        )
        val achievementIds = evaluateAchievements(candidate)
        val unlocked = achievementIds - candidate.achievements
        val finalState = candidate.copy(achievements = achievementIds)

        return MasteryAward(
            state = finalState,
            xpAwarded = xpAwarded,
            unlockedAchievementIds = unlocked,
            streakAdvanced = streak.advanced
        )
    }

    private fun updateRecentDays(
        existing: List<MasteryDaySnapshot>,
        event: MasteryEvent,
        epochDay: Long
    ): List<MasteryDaySnapshot> {
        val current = existing.firstOrNull { it.epochDay == epochDay }
            ?: MasteryDaySnapshot(epochDay)
        val updatedCounts = current.eventCounts.toMutableMap().apply {
            this[event] = (this[event] ?: 0) + 1
        }
        val updated = current.copy(eventCounts = updatedCounts)
        return (existing.filterNot { it.epochDay == epochDay } + updated)
            .sortedBy { it.epochDay }
            .takeLast(HISTORY_DAYS)
    }

    private data class StreakResult(
        val current: Int,
        val best: Int,
        val graceDays: Int,
        val advanced: Boolean
    )

    private fun advanceStreak(state: MasteryState, epochDay: Long): StreakResult {
        if (state.lastActiveEpochDay == epochDay) {
            return StreakResult(state.currentStreak, state.bestStreak, state.graceDays, false)
        }

        val gap = if (state.lastActiveEpochDay == MasteryState.NO_DAY) {
            Long.MAX_VALUE
        } else {
            epochDay - state.lastActiveEpochDay
        }

        var grace = state.graceDays
        val current = when {
            state.lastActiveEpochDay == MasteryState.NO_DAY -> 1
            gap == 1L -> state.currentStreak + 1
            gap == 2L && grace > 0 -> {
                grace -= 1
                state.currentStreak + 1
            }
            else -> 1
        }

        if (current > 0 && current % 7 == 0) {
            grace = min(MAX_GRACE_DAYS, grace + 1)
        }

        return StreakResult(
            current = current,
            best = maxOf(state.bestStreak, current),
            graceDays = grace,
            advanced = true
        )
    }

    private fun evaluateAchievements(state: MasteryState): Set<String> {
        val unlocked = state.achievements.toMutableSet()
        fun count(event: MasteryEvent): Int = state.eventCounts[event] ?: 0

        if (count(MasteryEvent.SWIPE_WORD) >= 1) unlocked += "first_flight"
        if (count(MasteryEvent.SHORTCUT_EXPANSION) >= 1) unlocked += "time_locksmith"
        if (count(MasteryEvent.AUTO_CORRECTION) >= 10) unlocked += "clear_signal"
        if (count(MasteryEvent.VOICE_LOCK_APPLY) >= 1) unlocked += "voice_found"
        if (count(MasteryEvent.OFFLINE_AI_APPLY) >= 1) unlocked += "private_pilot"
        if (count(MasteryEvent.TRANSLATION_APPLY) >= 1) unlocked += "polyglot_spark"
        if (count(MasteryEvent.REFINEMENT) >= 5) unlocked += "fine_tuned"
        if (state.bestStreak >= 7) unlocked += "steady_signal"
        return unlocked
    }
}

/** Compact, defensive persistence format for SharedPreferences. */
object MasteryStateCodec {
    private const val VERSION = 2
    private const val LEGACY_VERSION = 1

    fun encode(state: MasteryState): String = listOf(
        "v=$VERSION",
        "enabled=${if (state.enabled) 1 else 0}",
        "path=${encodeMap(state.pathXp)}",
        "events=${encodeMap(state.eventCounts)}",
        "day=${state.dailyEpochDay}",
        "dailyPath=${encodeMap(state.dailyPathXp)}",
        "dailyEvents=${encodeMap(state.dailyEventCounts)}",
        "current=${state.currentStreak}",
        "best=${state.bestStreak}",
        "last=${state.lastActiveEpochDay}",
        "grace=${state.graceDays}",
        "ach=${state.achievements.sorted().joinToString(",")}",
        "history=${encodeHistory(state.recentDays)}",
        "missionDay=${state.missionEpochDay}",
        "dismissed=${state.dismissedMissionIds.sorted().joinToString(",")}"
    ).joinToString(";")

    fun decode(raw: String?, enabledFallback: Boolean = true): MasteryState {
        if (raw.isNullOrBlank()) return MasteryState.fresh(enabledFallback)
        return try {
            val parts = raw.split(';')
                .mapNotNull { token ->
                    val index = token.indexOf('=')
                    if (index <= 0) null else token.substring(0, index) to token.substring(index + 1)
                }
                .toMap()
            val version = parts["v"]?.toIntOrNull()
            if (version != VERSION && version != LEGACY_VERSION) {
                return MasteryState.fresh(enabledFallback)
            }

            val dailyEpochDay = parts["day"]?.toLongOrNull() ?: MasteryState.NO_DAY
            val dailyEventCounts = decodeEnumMap<MasteryEvent>(parts["dailyEvents"])
            val recentDays = if (version == VERSION) {
                decodeHistory(parts["history"])
            } else if (dailyEpochDay != MasteryState.NO_DAY && dailyEventCounts.isNotEmpty()) {
                listOf(MasteryDaySnapshot(dailyEpochDay, dailyEventCounts))
            } else {
                emptyList()
            }

            MasteryState(
                enabled = parts["enabled"]?.toIntOrNull() != 0,
                pathXp = decodeEnumMap<MasteryPath>(parts["path"]).withPathDefaults(),
                eventCounts = decodeEnumMap(parts["events"]),
                dailyEpochDay = dailyEpochDay,
                dailyPathXp = decodeEnumMap<MasteryPath>(parts["dailyPath"]).withPathDefaults(),
                dailyEventCounts = dailyEventCounts,
                currentStreak = parts["current"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                bestStreak = parts["best"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                lastActiveEpochDay = parts["last"]?.toLongOrNull() ?: MasteryState.NO_DAY,
                graceDays = parts["grace"]?.toIntOrNull()
                    ?.coerceIn(0, KeyboardMastery.MAX_GRACE_DAYS) ?: 1,
                achievements = parts["ach"].orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                recentDays = recentDays,
                missionEpochDay = if (version == VERSION) {
                    parts["missionDay"]?.toLongOrNull() ?: MasteryState.NO_DAY
                } else {
                    MasteryState.NO_DAY
                },
                dismissedMissionIds = if (version == VERSION) {
                    parts["dismissed"].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
                } else {
                    emptySet()
                }
            )
        } catch (_: Exception) {
            MasteryState.fresh(enabledFallback)
        }
    }

    private fun encodeHistory(days: List<MasteryDaySnapshot>): String =
        days.sortedBy { it.epochDay }.joinToString("|") { day ->
            "${day.epochDay}~${encodeMap(day.eventCounts)}"
        }

    private fun decodeHistory(raw: String?): List<MasteryDaySnapshot> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('|').mapNotNull { token ->
            val pair = token.split('~', limit = 2)
            val day = pair.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            val counts = decodeEnumMap<MasteryEvent>(pair.getOrNull(1))
            MasteryDaySnapshot(day, counts)
        }.distinctBy { it.epochDay }
            .sortedBy { it.epochDay }
            .takeLast(KeyboardMastery.HISTORY_DAYS)
    }

    private fun <K : Enum<K>> encodeMap(map: Map<K, Int>): String =
        map.entries.sortedBy { it.key.name }.joinToString(",") { "${it.key.name}:${it.value}" }

    private inline fun <reified K : Enum<K>> decodeEnumMap(raw: String?): Map<K, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val values = enumValues<K>().associateBy { it.name }
        return raw.split(',').mapNotNull { token ->
            val pair = token.split(':', limit = 2)
            val key = values[pair.firstOrNull()] ?: return@mapNotNull null
            val value = pair.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0)
                ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    private fun Map<MasteryPath, Int>.withPathDefaults(): Map<MasteryPath, Int> =
        MasteryState.zeroPathMap() + this
}
