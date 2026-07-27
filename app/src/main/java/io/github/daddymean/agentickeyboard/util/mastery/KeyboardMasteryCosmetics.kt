package io.github.daddymean.agentickeyboard.util.mastery

/** Companion-app-only stages for the evolving Lumina constellation. */
enum class ConstellationStage(
    val label: String,
    val minTotalXp: Int,
    val visibleStars: Int
) {
    SPARK("Spark", 0, 4),
    ORBIT("Orbit", 100, 6),
    CLUSTER("Cluster", 250, 8),
    CONSTELLATION("Constellation", 500, 10),
    AURORA("Aurora", 900, 12)
}

/** Optional visual treatment. Unlocks never gate keyboard features. */
data class MasteryAura(
    val id: String,
    val title: String,
    val description: String,
    val symbol: String,
    val minTotalXp: Int = 0,
    val minAchievementCount: Int = 0,
    val minAllPathLevel: Int = 1,
    val minBestStreak: Int = 0
) {
    fun requirementLabel(): String = when {
        minBestStreak > 0 -> "Reach a $minBestStreak-day personal best"
        minAchievementCount > 0 -> "Unlock $minAchievementCount achievements"
        minAllPathLevel > 1 -> "Reach level $minAllPathLevel in every path"
        minTotalXp > 0 -> "Earn $minTotalXp total XP"
        else -> "Always available"
    }
}

data class MasteryCompanion(
    val stage: ConstellationStage,
    val aura: MasteryAura,
    val visibleStars: Int,
    val xpUntilNextStage: Int,
    val nextStage: ConstellationStage?,
    val nextAura: MasteryAura?
)

/** Deterministic cosmetic rules derived only from existing local mastery aggregates. */
object KeyboardMasteryCosmetics {
    const val DEFAULT_AURA_ID = "starlight"

    val auras = listOf(
        MasteryAura(
            id = DEFAULT_AURA_ID,
            title = "Starlight",
            description = "A calm blue glow for every constellation.",
            symbol = "✦"
        ),
        MasteryAura(
            id = "ember",
            title = "Ember",
            description = "A warm trail for a growing writing rhythm.",
            symbol = "✺",
            minTotalXp = 150
        ),
        MasteryAura(
            id = "prism",
            title = "Prism",
            description = "A balanced glow earned across all four paths.",
            symbol = "◇",
            minAllPathLevel = 2
        ),
        MasteryAura(
            id = "aurora",
            title = "Aurora",
            description = "A green shimmer powered by collected achievements.",
            symbol = "◈",
            minAchievementCount = 5
        ),
        MasteryAura(
            id = "comet",
            title = "Comet",
            description = "A rose trail for a durable personal rhythm.",
            symbol = "☄",
            minBestStreak = 14
        )
    )

    fun stageFor(state: MasteryState): ConstellationStage =
        ConstellationStage.values().last { state.totalXp >= it.minTotalXp }

    fun isUnlocked(state: MasteryState, aura: MasteryAura): Boolean =
        state.totalXp >= aura.minTotalXp &&
            state.achievements.size >= aura.minAchievementCount &&
            state.bestStreak >= aura.minBestStreak &&
            MasteryPath.values().all { state.levelFor(it) >= aura.minAllPathLevel }

    fun unlockedAuras(state: MasteryState): List<MasteryAura> =
        auras.filter { isUnlocked(state, it) }

    fun resolveSelectedAura(state: MasteryState, requestedId: String?): MasteryAura {
        val requested = auras.firstOrNull { it.id == requestedId }
        if (requested != null && isUnlocked(state, requested)) return requested
        return auras.first { it.id == DEFAULT_AURA_ID }
    }

    fun companion(state: MasteryState, requestedAuraId: String?): MasteryCompanion {
        val stage = stageFor(state)
        val nextStage = ConstellationStage.values().firstOrNull {
            it.minTotalXp > state.totalXp
        }
        val nextAura = auras.firstOrNull { !isUnlocked(state, it) }
        return MasteryCompanion(
            stage = stage,
            aura = resolveSelectedAura(state, requestedAuraId),
            visibleStars = stage.visibleStars,
            xpUntilNextStage = nextStage?.let { (it.minTotalXp - state.totalXp).coerceAtLeast(0) } ?: 0,
            nextStage = nextStage,
            nextAura = nextAura
        )
    }
}