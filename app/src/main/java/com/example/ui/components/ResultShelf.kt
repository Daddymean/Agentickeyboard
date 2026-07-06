package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ui.theme.KeyboardTheme
import com.example.ui.theme.LocalKeyboardColors

/**
 * Result shelf for AI outputs (summary, translate, etc.).
 * Extracted from monolith for maintainability.
 * Themed and expandable stub.
 */
@Composable
fun ResultShelf(
    resultText: String = "",
    isExpanded: Boolean = false,
    onExpand: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    KeyboardTheme {
        Card(
            modifier = modifier
                .fillMaxWidth()
                .padding(8.dp),
            colors = CardDefaults.cardColors(containerColor = LocalKeyboardColors.current.panel)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = if (isExpanded) resultText else resultText.take(50) + if (resultText.length > 50) "..." else "",
                    color = LocalKeyboardColors.current.text
                )
                if (!isExpanded && resultText.isNotEmpty()) {
                    TextButton(onClick = onExpand) {
                        Text("Expand", color = LocalKeyboardColors.current.accent)
                    }
                }
            }
        }
    }
}
