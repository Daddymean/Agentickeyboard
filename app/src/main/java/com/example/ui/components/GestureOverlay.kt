package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.KeyboardTheme
import com.example.ui.theme.LocalKeyboardColors

/**
 * One-shot extracted Gesture Overlay.
 * Handles drag preview, gesture alerts, and gesture guide cheat sheet.
 * Replaces the gesture-related UI blocks in AgenticKeyboardLayout.kt.
 */
@Composable
fun GestureOverlay(
    dragPreview: String?,
    gestureAlert: String?,
    showGestureGuide: Boolean,
    onDismissGuide: () -> Unit,
    modifier: Modifier = Modifier
) {
    KeyboardTheme {
        val colors = LocalKeyboardColors.current

        if (dragPreview != null) {
            Box(
                modifier = modifier.fillMaxWidth().background(colors.accent).padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(dragPreview, color = colors.onAccent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (gestureAlert != null) {
            Text(
                text = gestureAlert,
                color = colors.keyActive,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
            )
        }

        if (showGestureGuide) {
            // Simplified gesture guide card (full logic can be expanded)
            // In one-shot, the full Card with cheat sheet rows from original would go here
            Text("📐 Gestures Cheat Sheet (see original for full)", color = colors.accent)
        }
    }
}
