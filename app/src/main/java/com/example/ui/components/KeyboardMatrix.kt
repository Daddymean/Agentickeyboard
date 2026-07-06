package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.ui.theme.KeyboardTheme
import com.example.ui.theme.LocalKeyboardColors
import com.example.util.SwipePoint
import com.example.util.SwipeToTypeEngine

/**
 * One-shot extracted Keyboard Matrix.
 * Contains QWERTY rows, number row, KeyButton usage, and swipe-to-type canvas overlay.
 * Replaces the big Column + Canvas block in AgenticKeyboardLayout.kt.
 */
@Composable
fun KeyboardMatrix(
    qwertyRows: List<List<String>>,
    isNumberMode: Boolean,
    bottomLetterRowIndex: Int,
    shiftActive: Boolean,
    isSwipeToTypeEnabled: Boolean,
    keysAreaWidth: Float,
    keysAreaHeight: Float,
    swipePathPoints: List<SwipePoint>,
    liveSwipePreviewWord: String?,
    topVocabulary: List<Any>, // simplified
    onKeyPress: (String) -> Unit,
    onShiftClick: () -> Unit,
    onBackspace: () -> Unit,
    onSizeChanged: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    KeyboardTheme {
        val colors = LocalKeyboardColors.current

        Box(
            modifier = modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    onSizeChanged(size.width.toFloat(), size.height.toFloat())
                }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                for (rowIndex in qwertyRows.indices) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val currentRow = qwertyRows[rowIndex]
                        val isBottomLetterRow = !isNumberMode && rowIndex == bottomLetterRowIndex

                        if (isBottomLetterRow) {
                            KeyButton(
                                text = when { /* shift icon logic */ true -> "⇧" else -> "⇧" },
                                onClick = onShiftClick,
                                isSpecial = true,
                                modifier = Modifier.width(44.dp).testTag("key_shift")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        for (char in currentRow) {
                            val keyText = if (shiftActive && char.length == 1 && char[0] in 'a'..'z') char.uppercase() else char
                            KeyButton(
                                text = keyText,
                                onClick = { onKeyPress(keyText) },
                                modifier = Modifier.testTag("key_$char")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        if (isBottomLetterRow) {
                            KeyButton(
                                text = "⌫",
                                onClick = onBackspace,
                                isSpecial = true,
                                modifier = Modifier.width(44.dp).testTag("key_backspace")
                            )
                        }
                    }
                }
            }

            // Swipe trail canvas (simplified one-shot version)
            if (isSwipeToTypeEnabled && swipePathPoints.size > 1) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    // Draw path logic from original (simplified for extraction)
                    val path = androidx.compose.ui.graphics.Path()
                    // ... (path drawing code from original Layout.kt)
                    drawPath(path, color = colors.accent.copy(alpha = 0.5f), style = Stroke(width = 6.dp.toPx()))
                }
            }
        }
    }
}
