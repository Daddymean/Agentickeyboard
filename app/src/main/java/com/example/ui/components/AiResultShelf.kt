package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.KeyboardTheme
import com.example.ui.theme.LocalKeyboardColors
import com.example.util.ReplyIntents

/**
 * One-shot extracted AI Result Shelf.
 * Replaces the massive inline AI results block in AgenticKeyboardLayout.kt.
 * Handles grammar, tone, summary, translate, rewrite, compose, continue, explanation, suggestions.
 * Includes iterate chips, regenerate, and dismiss.
 * Themed via KeyboardTheme.
 */
@Composable
fun AiResultShelf(
    // State from ViewModel (collect in caller or pass here)
    isLoading: Boolean,
    grammarCorrection: Any?, // Replace with actual data class
    toneAnalysis: Any?,
    summary: String?,
    translation: String?,
    rewrite: String?,
    composeResult: String?,
    continuation: String?,
    explanation: String?,
    suggestions: List<String>,
    sendGuardWarning: Any?,
    hasAiResult: Boolean,
    aiResultSource: String?,
    effectivePersona: String,
    // Callbacks
    onApply: (String) -> Unit,
    onAppend: (String) -> Unit,
    onDismiss: () -> Unit,
    onRegenerate: () -> Unit,
    onRefine: (String) -> Unit,
    onChooseReplyIntent: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    KeyboardTheme {
        val colors = LocalKeyboardColors.current

        // Local expanded state for results
        var resultExpanded by remember(
            grammarCorrection, summary, translation, rewrite, composeResult, continuation
        ) { mutableStateOf(false) }

        Box(
            modifier = modifier
                .fillMaxWidth()
                .then(if (resultExpanded) Modifier.heightIn(min = 64.dp) else Modifier.height(64.dp))
                .animateContentSize()
                .background(colors.shelf)
                .padding(horizontal = 8.dp, vertical = if (resultExpanded) 8.dp else 0.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = colors.accent)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Agentic AI processing...", color = colors.text, fontSize = 12.sp)
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        // Send Guard Warning
                        if (sendGuardWarning != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("⚠️ This might land harshly", color = colors.error, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("Keep editing, or send it as-is.", color = colors.textMuted, fontSize = 10.sp, maxLines = 1)
                                }
                                // Revise + Send anyway buttons (simplified for extraction)
                                Button(
                                    onClick = { /* caller handles */ },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.error),
                                    modifier = Modifier.height(32.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Text("Send anyway", color = colors.onError, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } 
                        // Grammar Correction
                        else if (grammarCorrection != null) {
                            // Simplified - in real use pass proper data class
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Corrected: [result]", color = colors.success, fontSize = 13.sp)
                                Button(onClick = { onApply("[corrected]") }, colors = ButtonDefaults.buttonColors(containerColor = colors.accent)) {
                                    Text("Apply", color = colors.onAccent, fontSize = 11.sp)
                                }
                            }
                        }
                        // Tone Analysis
                        else if (toneAnalysis != null) {
                            Text("🎭 Tone Analysis [details]", color = colors.onChip, fontSize = 13.sp)
                            // Emoji chips + suggestions would go here (simplified for one-shot)
                        }
                        // Summary
                        else if (summary != null) {
                            Row(...) { /* similar pattern */ }
                        }
                        // Translation, Rewrite, Compose, Continuation, Explanation, Suggestions
                        else if (translation != null || rewrite != null || composeResult != null || continuation != null || explanation != null || suggestions.isNotEmpty()) {
                            Text("AI Result: [dynamic based on which is non-null]", color = colors.text, fontSize = 13.sp)
                            // Buttons for Apply/Append/Use
                        } 
                        else {
                            // Default / predictive suggestions row (simplified)
                            Text("🚀 Agentic Online Mode", color = colors.accent, fontSize = 11.sp)
                        }
                    }

                    if (hasAiResult) {
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(28.dp).testTag("regenerate_result")) {
                            Text("↻", color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp).testTag("dismiss_results")) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Dismiss", tint = colors.textMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        // Iterate Chips
        val hasRefinableResult = grammarCorrection != null || summary != null || translation != null || rewrite != null || composeResult != null || continuation != null
        AnimatedVisibility(visible = hasRefinableResult && !isLoading, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier.fillMaxWidth().background(colors.shelf).padding(horizontal = 8.dp, vertical = 2.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("Shorter", "Longer", "Warmer", "Firmer", "More formal").forEach { adjustment ->
                    // ClipActionChip equivalent
                    Box(modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(colors.chip).clickable { onRefine(adjustment) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(adjustment, color = colors.onChip, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// Note: This is a one-shot aggressive extraction. In production, replace the Any? with proper data classes
// and move all the detailed row logic from the original Layout.kt into this file for true one-shot replacement.
// The caller (Layout.kt) will pass collected state and simple lambdas.