package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.theme.KeyboardTheme
import com.example.ui.theme.LocalKeyboardColors

/**
 * AI Action Row for the keyboard shelf.
 * Demonstrates integration of SelectionBadge for discoverability when text selection is active.
 * Uses KeyboardTheme for consistent light/dark support.
 * This is the start of extracting the AI shelf from the monolith.
 */
@Composable
fun AiActionRow(
    hasActiveSelection: Boolean = false,
    onAction: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    KeyboardTheme {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Example AI action chips - in real use, these would be dynamic from ViewModel
            AssistChip(
                onClick = { onAction("fixGrammar") },
                label = { Text("Fix Grammar", color = LocalKeyboardColors.current.onChip) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = LocalKeyboardColors.current.chip
                )
            )
            AssistChip(
                onClick = { onAction("rewrite") },
                label = { Text("Rewrite", color = LocalKeyboardColors.current.onChip) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = LocalKeyboardColors.current.chipAlt
                )
            )

            Spacer(modifier = Modifier.weight(1f))

            // Selection indicator badge - only shown when selection is active
            if (hasActiveSelection) {
                SelectionBadge()
            }

            // Placeholder for more actions
            AssistChip(
                onClick = { onAction("summarize") },
                label = { Text("Summarize", color = LocalKeyboardColors.current.onChip) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = LocalKeyboardColors.current.chip
                )
            )
        }
    }
}
