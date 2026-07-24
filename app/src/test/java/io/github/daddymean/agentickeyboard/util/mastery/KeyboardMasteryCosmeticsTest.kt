package io.github.daddymean.agentickeyboard.util.mastery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardMasteryCosmeticsTest {

    @Test
    fun starlightIsAlwaysAvailable() {
        val state = MasteryState.fresh()

        assertTrue(
            KeyboardMasteryCosmetics.unlockedAuras(state)
                .any { it.id == KeyboardMasteryCosmetics.DEFAULT_AURA_ID }
        )
    }

    @Test
    fun lockedSelectionFallsBackToDefault() {
        val selected = KeyboardMasteryCosmetics.resolveSelectedAura(
            MasteryState.fresh(),
            requestedId = "comet"
        )

        assertEquals(KeyboardMasteryCosmetics.DEFAULT_AURA_ID, selected.id)
    }

    @Test
    fun xpUnlocksEmberAndAdvancesStage() {
        val state = MasteryState(
            pathXp = MasteryPath.values().associateWith { path ->
                if (path == MasteryPath.FLOW) 260 else 0
            }
        )

        assertTrue(KeyboardMasteryCosmetics.unlockedAuras(state).any { it.id == "ember" })
        assertEquals(ConstellationStage.CLUSTER, KeyboardMasteryCosmetics.stageFor(state))
    }

    @Test
    fun balancedPathLevelsUnlockPrism() {
        val state = MasteryState(
            pathXp = MasteryPath.values().associateWith { MasteryState.XP_PER_LEVEL }
        )

        assertTrue(KeyboardMasteryCosmetics.unlockedAuras(state).any { it.id == "prism" })
    }

    @Test
    fun achievementsAndStreakUnlockTheirOwnAuras() {
        val state = MasteryState(
            achievements = setOf("a", "b", "c", "d", "e"),
            bestStreak = 14
        )
        val ids = KeyboardMasteryCosmetics.unlockedAuras(state).map { it.id }.toSet()

        assertTrue("aurora" in ids)
        assertTrue("comet" in ids)
    }

    @Test
    fun companionReportsTheNextStageWithoutMutatingProgress() {
        val state = MasteryState(
            pathXp = MasteryPath.values().associateWith { path ->
                if (path == MasteryPath.CLARITY) 120 else 0
            }
        )

        val companion = KeyboardMasteryCosmetics.companion(state, "starlight")

        assertEquals(ConstellationStage.ORBIT, companion.stage)
        assertEquals(ConstellationStage.CLUSTER, companion.nextStage)
        assertEquals(130, companion.xpUntilNextStage)
        assertEquals(120, state.totalXp)
    }

    @Test
    fun cosmeticCatalogNeverTargetsInputMethodSelection() {
        val text = KeyboardMasteryCosmetics.auras.joinToString(" ") {
            "${it.id} ${it.title} ${it.description} ${it.requirementLabel()}"
        }.lowercase()

        assertFalse("default keyboard" in text)
        assertFalse("input method" in text)
        assertFalse("enable keyboard" in text)
    }
}