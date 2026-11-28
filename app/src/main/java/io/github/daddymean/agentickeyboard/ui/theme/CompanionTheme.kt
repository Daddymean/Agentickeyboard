package io.github.daddymean.agentickeyboard.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Semantic palette for the companion app's Bento UI (MainActivity, Console,
 * Shortcuts, Style Hub, and Setup Guide). Mirrors [KeyboardColors] so Light,
 * Dark, and System modes flow cleanly through one root provider instead of
 * scattered `Color(0xFF...)` literals.
 */
data class CompanionColors(
    val background: Color,          // screen / scaffold canvas
    val card: Color,                // primary Bento card surface
    val cardSubtle: Color,          // input containers & nested rows
    val cardTinted: Color,          // soft lavender Bento card
    val cardAccent: Color,          // hero lavender card / selected chip fill
    val cardAccentBadge: Color,     // badge fill inside hero accent card
    val cardDark: Color,            // high-contrast dark telemetry / privacy card
    val cardDarkChip: Color,        // icon pill inside cardDark
    val onCardDark: Color,          // primary text on cardDark
    val onCardDarkAccent: Color,    // bright green stat / code text on cardDark
    val surfaceMuted: Color,        // unselected chips, secondary buttons, badges
    val keyboardFrame: Color,       // simulation keyboard container
    val border: Color,              // default 1dp card/nav border
    val borderStrong: Color,        // input outlines & secondary button borders
    val textPrimary: Color,         // headings & primary body text
    val textSecondary: Color,       // subtitles & helper copy
    val textTertiary: Color,        // muted metadata / empty state text
    val accent: Color,              // primary brand purple
    val onAccent: Color,            // text/icons on accent buttons
    val onAccentContainer: Color,   // text on cardAccent / selected chips
    val pinkContainer: Color,       // shortcut redirect icon pill
    val onPinkContainer: Color,     // shortcut redirect heading
    val success: Color,             // status dot, active key, positive indicator
    val successContainer: Color,    // privacy shield banner fill
    val onSuccessContainer: Color,  // privacy shield heading
    val danger: Color,              // destructive buttons / delete icon / typo text
    val warning: Color,             // cloud-enabled indicator on dark card
    val info: Color,                // translation & restore link accent
)

val LightCompanionColors = CompanionColors(
    background = Color(0xFFF3F4F9),
    card = Color.White,
    cardSubtle = Color(0xFFF8FAFC),
    cardTinted = Color(0xFFF7F2FA),
    cardAccent = Color(0xFFE8DEF8),
    cardAccentBadge = Color(0xFFD0BCFF),
    cardDark = Color(0xFF1C1B1F),
    cardDarkChip = Color(0xFF334155),
    onCardDark = Color.White,
    onCardDarkAccent = Color(0xFF4ADE80),
    surfaceMuted = Color(0xFFF1F5F9),
    keyboardFrame = Color(0xFFE6E1E5),
    border = Color(0xFFE2E8F0),
    borderStrong = Color(0xFFCBD5E1),
    textPrimary = Color(0xFF1C1B1F),
    textSecondary = Color(0xFF5F5D6B),
    textTertiary = Color(0xFF475569),
    accent = Color(0xFF6750A4),
    onAccent = Color.White,
    onAccentContainer = Color(0xFF21005D),
    pinkContainer = Color(0xFFFFD8E4),
    onPinkContainer = Color(0xFF31111D),
    success = Color(0xFF2E7D32),
    successContainer = Color(0xFFE8F5E9),
    onSuccessContainer = Color(0xFF1B5E20),
    danger = Color(0xFFC62828),
    warning = Color(0xFFFBBF24),
    info = Color(0xFF0369A1),
)

val DarkCompanionColors = CompanionColors(
    background = Color(0xFF121318),
    card = Color(0xFF1C1E26),
    cardSubtle = Color(0xFF232632),
    cardTinted = Color(0xFF242030),
    cardAccent = Color(0xFF342A4C),
    cardAccentBadge = Color(0xFF4F3D78),
    cardDark = Color(0xFF0E1016),
    cardDarkChip = Color(0xFF273244),
    onCardDark = Color(0xFFF1F5F9),
    onCardDarkAccent = Color(0xFF4ADE80),
    surfaceMuted = Color(0xFF282B38),
    keyboardFrame = Color(0xFF1C1B1F),
    border = Color(0xFF313545),
    borderStrong = Color(0xFF454B60),
    textPrimary = Color(0xFFECEBF2),
    textSecondary = Color(0xFFA9A7B8),
    textTertiary = Color(0xFF94A3B8),
    accent = Color(0xFFD0BCFF),
    onAccent = Color(0xFF21005D),
    onAccentContainer = Color(0xFFEADDFF),
    pinkContainer = Color(0xFF5D3B4A),
    onPinkContainer = Color(0xFFFFD8E4),
    success = Color(0xFF6EE7B7),
    successContainer = Color(0xFF1B3A26),
    onSuccessContainer = Color(0xFFA7F3D0),
    danger = Color(0xFFF2B8B5),
    warning = Color(0xFFFCD34D),
    info = Color(0xFF7EC8F0),
)

val LocalCompanionColors = staticCompositionLocalOf { LightCompanionColors }

/** Resolves the user's `"Light"` / `"Dark"` / `"System"` preference against [systemDark]. */
fun resolveDarkTheme(themeOverride: String, systemDark: Boolean): Boolean = when (themeOverride) {
    "Light" -> false
    "Dark" -> true
    else -> systemDark
}

@Composable
fun CompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkCompanionColors else LightCompanionColors
    MyApplicationTheme(darkTheme = darkTheme) {
        CompositionLocalProvider(LocalCompanionColors provides colors) {
            content()
        }
    }
}
