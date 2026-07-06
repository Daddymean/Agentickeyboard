package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ui.theme.KeyboardTheme
import com.example.ui.theme.LocalKeyboardColors

/**
 * Basic themed keyboard key component.
 * Part of monolith split - replace hardcoded keys in AgenticKeyboardLayout.kt.
 * Supports normal, special, and active states. Uses full theming.
 */
@Composable
fun KeyboardKey(
    text: String,
    isSpecial: Boolean = false,
    isActive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    KeyboardTheme {
        val colors = LocalKeyboardColors.current
        val backgroundColor = when {
            isActive -> colors.keyActive
            isSpecial -> colors.keySpecial
            else -> colors.key
        }

        Box(
            modifier = modifier
                .background(backgroundColor)
                .clickable(onClick = onClick)
                .padding(8.dp)
                .sizeIn(minWidth = 36.dp, minHeight = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                color = colors.text,
                textAlign = TextAlign.Center
            )
        }
    }
}
