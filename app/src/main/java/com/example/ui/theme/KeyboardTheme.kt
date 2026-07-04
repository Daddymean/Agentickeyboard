package com.example.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Palette for the keyboard surface. Every color the keyboard draws comes from
 * here so light/dark theming is a single switch instead of scattered hex values.
 */
data class KeyboardColors(
    val background: Color,   // keyboard body
    val shelf: Color,        // suggestion shelf / AI action row
    val panel: Color,        // cards (gesture guide, clipboard panel)
    val key: Color,          // normal key
    val keySpecial: Color,   // modifier keys (shift, mode, ...)
    val keyActive: Color,    // engaged modifier (shift held, caps lock)
    val border: Color,
    val text: Color,         // primary text
    val textMuted: Color,
    val accent: Color,       // action buttons, Enter key
    val onAccent: Color,     // text on accent
    val chip: Color,         // tips/replies chip background
    val chipAlt: Color,      // predictive suggestion chip background
    val onChip: Color,       // text on chips
    val success: Color,
    val successChip: Color,  // proofread hint chip background
    val emojiChip: Color,    // tone emoji chip background
    val popup: Color         // key preview / accent variant popup
)

val LightKeyboardColors = KeyboardColors(
    background = Color(0xFFE6E1E5),
    shelf = Color(0xFFF7F2FA),
    panel = Color(0xFFF3EDF7),
    key = Color.White,
    keySpecial = Color(0xFFE7E0EC),
    keyActive = Color(0xFFD0BCFF),
    border = Color(0xFFCBD5E1),
    text = Color(0xFF1C1B1F),
    textMuted = Color(0xFF6F6D76),
    accent = Color(0xFF6750A4),
    onAccent = Color.White,
    chip = Color(0xFFE8DEF8),
    chipAlt = Color(0xFFEADDFF),
    onChip = Color(0xFF21005D),
    success = Color(0xFF15803D),
    successChip = Color(0xFFDCFCE7),
    emojiChip = Color(0xFFFFD8E4),
    popup = Color.White
)

val DarkKeyboardColors = KeyboardColors(
    background = Color(0xFF1C1B1F),
    shelf = Color(0xFF211F26),
    panel = Color(0xFF2B2930),
    key = Color(0xFF36343B),
    keySpecial = Color(0xFF48464C),
    keyActive = Color(0xFF6750A4),
    border = Color(0xFF48464C),
    text = Color(0xFFE6E1E5),
    textMuted = Color(0xFFA8A3AD),
    accent = Color(0xFFD0BCFF),
    onAccent = Color(0xFF381E72),
    chip = Color(0xFF4A4458),
    chipAlt = Color(0xFF4F378B),
    onChip = Color(0xFFE8DEF8),
    success = Color(0xFF7EE2A8),
    successChip = Color(0xFF1E3A2A),
    emojiChip = Color(0xFF5D3B4A),
    popup = Color(0xFF2B2930)
)

val LocalKeyboardColors = staticCompositionLocalOf { LightKeyboardColors }
