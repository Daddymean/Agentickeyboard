package io.github.daddymean.agentickeyboard.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionThemeTest {

    @Test
    fun resolveDarkThemeHonorsExplicitOverridesAndFallsBackToSystem() {
        assertTrue(resolveDarkTheme("Dark", systemDark = false))
        assertTrue(resolveDarkTheme("Dark", systemDark = true))
        assertFalse(resolveDarkTheme("Light", systemDark = true))
        assertFalse(resolveDarkTheme("Light", systemDark = false))
        assertTrue(resolveDarkTheme("System", systemDark = true))
        assertFalse(resolveDarkTheme("System", systemDark = false))
        assertTrue(resolveDarkTheme("Unexpected", systemDark = true))
        assertFalse(resolveDarkTheme("Unexpected", systemDark = false))
    }

    @Test
    fun lightAndDarkPalettesUseDistinctSurfacesAndText() {
        assertNotEquals(LightCompanionColors.background, DarkCompanionColors.background)
        assertNotEquals(LightCompanionColors.card, DarkCompanionColors.card)
        assertNotEquals(LightCompanionColors.textPrimary, DarkCompanionColors.textPrimary)
        assertNotEquals(LightCompanionColors.accent, DarkCompanionColors.accent)
        assertNotEquals(LightCompanionColors.card, LightCompanionColors.textPrimary)
        assertNotEquals(DarkCompanionColors.card, DarkCompanionColors.textPrimary)
    }
}
