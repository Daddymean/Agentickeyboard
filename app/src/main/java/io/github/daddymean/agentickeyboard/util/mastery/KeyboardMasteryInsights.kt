package io.github.daddymean.agentickeyboard.util.mastery

import kotlin.math.ceil

data class MasteryMissionDefinition(
    val id: String,
    val title: String,
    val description: String,
    val path: MasteryPath,
    val events: Set<MasteryEvent>,
    val target: Int
)

data class MasteryMission(
    val definition: MasteryMissionDefinition,
    val progress: Int
) {
    val completed: Boolean get() = progress >= definition.target
    val clampedProgress: Int get() = progress.coerceAtMost(definition.target)
    val progressFraction: Float
        get() = (clampedProgress.toFloat() / definition.target.toFloat()).coerceIn(0f, 1f)
}

/** Optional, penalty-free missions selected from feature-usage gaps. */
object KeyboardMasteryMissions {
    val catalog = listOf(
        MasteryMissionDefinition(
            id = "flow_swipe_five",
            title = "Take the glide path",
            description = "Swipe five words.",
            path = MasteryPath.FLOW,
            events = setOf(MasteryEvent.SWIPE_WORD),
            target = 5
        ),
        MasteryMissionDefinition(
            id = "flow_shortcut_once",
            title = "Open a time shortcut",
            description = "Expand one saved shortcut.",
            path = MasteryPath.FLOW,
            events = setOf(MasteryEvent.SHORTCUT_EXPANSION),
            target = 1
        ),
        MasteryMissionDefinition(
            id = "clarity_three_fixes",
            title = "Polish the signal",
            description = "Accept three automatic corrections.",
            path = MasteryPath.CLARITY,
            events = setOf(MasteryEvent.AUTO_CORRECTION),
            target = 3
        ),
        MasteryMissionDefinition(
            id = "clarity_ai_apply",
            title = "Put AI to work",
            description = "Apply one AI result.",
            path = MasteryPath.CLARITY,
            events = setOf(
                MasteryEvent.AI_APPLY,
                MasteryEvent.VOICE_LOCK_APPLY,
                MasteryEvent.OFFLINE_AI_APPLY,
                MasteryEvent.TRANSLATION_APPLY
            ),
            target = 1
        ),
        MasteryMissionDefinition(
            id = "voice_refine_once",
            title = "Tune the voice",
            description = "Use one refinement chip.",
            path = MasteryPath.VOICE,
            events = setOf(MasteryEvent.REFINEMENT),
            target = 1
        ),
        MasteryMissionDefinition(
            id = "voice_translate_once",
            title = "Cross a language bridge",
            description = "Apply one translation.",
            path = MasteryPath.VOICE,
            events = setOf(MasteryEvent.TRANSLATION_APPLY),
            target = 1
        ),
        MasteryMissionDefinition(
            id = "voice_lock_once",
            title = "Keep your fingerprint",
            description = "Apply one Voice Lock rewrite.",
            path = MasteryPath.VOICE,
            events = setOf(MasteryEvent.VOICE_LOCK_APPLY),
            target = 1
        ),
        MasteryMissionDefinition(
            id = "trust_offline_ai",
            title = "Fly private",
            description = "Apply one offline AI result.",
            path = MasteryPath.TRUST,
            events = setOf(MasteryEvent.OFFLINE_AI_APPLY),
            target = 1
        )
    )

    private val knownIds = catalog.mapTo(mutableSetOf()) { it.id }

    fun forDay(
        state: MasteryState,
        epochDay: Long,
        limit: Int = 3
    ): List<MasteryMission> {
        if (!state.enabled || limit <= 0) return emptyList()
        val dismissed = if (state.missionEpochDay == epochDay) {
            state.dismissedMissionIds
        } else {
            emptySet()
        }
        val today = state.snapshotFor(epochDay)
        val sorted = catalog.asSequence()
            .filterNot { it.id in dismissed }
            .map { definition ->
                val progress = definition.events.sumOf { today?.count(it) ?: 0 }
                val lifetime = definition.events.sumOf { state.eventCounts[it] ?: 0 }
                val mission = MasteryMission(definition, progress)
                MissionCandidate(
                    mission = mission,
                    lifetimeCount = lifetime,
                    rotation = Math.floorMod(
                        definition.id.hashCode() + epochDay.hashCode(),
                        10_000
                    )
                )
            }
            .sortedWith(
                compareBy<MissionCandidate> { it.mission.completed }
                    .thenBy { it.lifetimeCount > 0 }
                    .thenBy { it.lifetimeCount }
                    .thenBy { it.rotation }
            )
            .toList()

        val selected = mutableListOf<MissionCandidate>()
        sorted.forEach { candidate ->
            if (selected.size < limit && selected.none {
                    it.mission.definition.path == candidate.mission.definition.path
                }) {
                selected += candidate
            }
        }
        sorted.forEach { candidate ->
            if (selected.size < limit && candidate !in selected) selected += candidate
        }
        return selected.take(limit).map { it.mission }
    }

    fun dismiss(state: MasteryState, missionId: String, epochDay: Long): MasteryState {
        if (missionId !in knownIds) return state
        val dismissed = if (state.missionEpochDay == epochDay) {
            state.dismissedMissionIds
        } else {
            emptySet()
        }
        return state.copy(
            missionEpochDay = epochDay,
            dismissedMissionIds = dismissed + missionId
        )
    }

    private data class MissionCandidate(
        val mission: MasteryMission,
        val lifetimeCount: Int,
        val rotation: Int
    )
}

data class WeeklyMasteryReport(
    val startEpochDay: Long,
    val endEpochDay: Long,
    val eventCounts: Map<MasteryEvent, Int>,
    val previousEventCounts: Map<MasteryEvent, Int>,
    val activeDays: Int,
    val previousActiveDays: Int,
    val totalActions: Int,
    val previousTotalActions: Int,
    val estimatedKeystrokesSaved: Int,
    val estimatedSecondsSaved: Int,
    val dominantPath: MasteryPath?,
    val bestDayEpochDay: Long?,
    val bestDayActions: Int
) {
    val actionDelta: Int get() = totalActions - previousTotalActions
}

/** Rolling seven-day summaries computed from the bounded local history. */
object KeyboardMasteryReports {
    private val keystrokesSavedPerEvent = mapOf(
        MasteryEvent.AUTO_CORRECTION to 2,
        MasteryEvent.SWIPE_WORD to 2,
        MasteryEvent.AI_APPLY to 30,
        MasteryEvent.SHORTCUT_EXPANSION to 12,
        MasteryEvent.VOICE_LOCK_APPLY to 30,
        MasteryEvent.OFFLINE_AI_APPLY to 30,
        MasteryEvent.TRANSLATION_APPLY to 20,
        MasteryEvent.REFINEMENT to 8
    )
    private const val ESTIMATED_KEYSTROKES_PER_MINUTE = 200

    fun rollingWeek(state: MasteryState, epochDay: Long): WeeklyMasteryReport {
        val currentStart = epochDay - 6
        val previousStart = epochDay - 13
        val previousEnd = epochDay - 7
        val currentDays = state.recentDays.filter { it.epochDay in currentStart..epochDay }
        val previousDays = state.recentDays.filter { it.epochDay in previousStart..previousEnd }
        val currentCounts = aggregate(currentDays)
        val previousCounts = aggregate(previousDays)
        val totalActions = currentCounts.values.sum()
        val previousTotal = previousCounts.values.sum()
        val saved = currentCounts.entries.sumOf { (event, count) ->
            count * (keystrokesSavedPerEvent[event] ?: 0)
        }
        val seconds = if (saved == 0) {
            0
        } else {
            ceil(saved * 60.0 / ESTIMATED_KEYSTROKES_PER_MINUTE).toInt()
        }
        val pathCounts = MasteryPath.values().associateWith { path ->
            currentCounts.entries.sumOf { (event, count) -> if (event.path == path) count else 0 }
        }
        val dominant = pathCounts.maxByOrNull { it.value }
            ?.takeIf { it.value > 0 }
            ?.key
        val bestDay = state.recentDays.maxWithOrNull(
            compareBy<MasteryDaySnapshot> { it.totalEvents }.thenBy { it.epochDay }
        )

        return WeeklyMasteryReport(
            startEpochDay = currentStart,
            endEpochDay = epochDay,
            eventCounts = currentCounts,
            previousEventCounts = previousCounts,
            activeDays = currentDays.count { it.totalEvents > 0 },
            previousActiveDays = previousDays.count { it.totalEvents > 0 },
            totalActions = totalActions,
            previousTotalActions = previousTotal,
            estimatedKeystrokesSaved = saved,
            estimatedSecondsSaved = seconds,
            dominantPath = dominant,
            bestDayEpochDay = bestDay?.epochDay,
            bestDayActions = bestDay?.totalEvents ?: 0
        )
    }

    private fun aggregate(days: List<MasteryDaySnapshot>): Map<MasteryEvent, Int> {
        val counts = mutableMapOf<MasteryEvent, Int>()
        days.forEach { day ->
            day.eventCounts.forEach { (event, count) ->
                counts[event] = (counts[event] ?: 0) + count
            }
        }
        return counts
    }
}
