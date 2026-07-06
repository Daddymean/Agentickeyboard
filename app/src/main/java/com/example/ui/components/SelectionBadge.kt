package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ui.theme.KeyboardTheme
import com.example.ui.theme.LocalKeyboardColors

/**
 * Small discoverability badge for when AI actions are operating on an active text selection.
 * Part of the selection-scope indicator (ROADMAP).
 * Uses the new KeyboardTheme provider for consistent theming.
 */
@Composable
fun SelectionBadge(
    modifier: Modifier = Modifier
) {
    KeyboardTheme {  // Ensures themed colors
        Text(
            text = "Acting on selection",
            modifier = modifier
                .background(LocalKeyboardColors.current.accent.copy(alpha = 0.2f))
                .padding(horizontal = 8.dp, vertical = 2.dp),
            color = LocalKeyboardColors.current.accent,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall
        )
    }
}
