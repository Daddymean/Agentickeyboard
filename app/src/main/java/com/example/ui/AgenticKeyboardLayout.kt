package com.example.ui

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.layout.onSizeChanged
import com.example.util.SwipePoint
import com.example.util.SwipeToTypeEngine
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun AgenticKeyboardLayout(
    viewModel: KeyboardViewModel,
    modifier: Modifier = Modifier,
    onKeyPress: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onAction: () -> Unit = {},
    currentInputConnection: InputConnection? = null,
    inPlaygroundMode: Boolean = false,
    playgroundTextState: String = "",
    onPlaygroundTextChange: (String) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    var isCapsLock by remember { mutableStateOf(false) }
    var isNumberMode by remember { mutableStateOf(false) }

    // Collect states from ViewModel
    val isLoading by viewModel.isLoading.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val grammarCorrection by viewModel.grammarCorrection.collectAsState()
    val toneAnalysis by viewModel.toneAnalysis.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val translation by viewModel.translation.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val predictiveSuggestions by viewModel.predictiveSuggestions.collectAsState()
    val topVocabulary by viewModel.topVocabulary.collectAsState()

    // Swipe gesture metrics
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var gestureAlert by remember { mutableStateOf<String?>(null) }
    var dragPreview by remember { mutableStateOf<String?>(null) }
    var showGestureGuide by remember { mutableStateOf(false) }

    // Swipe-to-Type Gesture States
    var isSwipeToTypeEnabled by remember { mutableStateOf(true) }
    var keysAreaWidth by remember { mutableFloatStateOf(0f) }
    var keysAreaHeight by remember { mutableFloatStateOf(0f) }
    val swipePathPoints = remember { mutableStateListOf<SwipePoint>() }
    var liveSwipePreviewWord by remember { mutableStateOf<String?>(null) }

    // Read context text from the input connection or the playground state
    val activeText = if (inPlaygroundMode) playgroundTextState else {
        currentInputConnection?.getTextBeforeCursor(1000, 0)?.toString() ?: ""
    }

    // Active AI actions visibility
    var showAiActions by remember { mutableStateOf(true) }

    val bentoKeyboardBg = Color(0xFFE6E1E5)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bentoKeyboardBg)
            .padding(bottom = 8.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = {
                        val deltaX = dragX
                        val deltaY = dragY
                        dragX = 0f
                        dragY = 0f
                        dragPreview = null
                        
                        if (abs(deltaX) > 100 && abs(deltaY) > 100) {
                            if (deltaX > 0 && deltaY < 0) {
                                // Up-Right -> Translate
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                gestureAlert = "Gesture Triggered: Translating! 🌐"
                                viewModel.translateText(activeText)
                            } else if (deltaX < 0 && deltaY < 0) {
                                // Up-Left -> Summarize
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                gestureAlert = "Gesture Triggered: Summarizing! 🔍"
                                viewModel.summarizeMessage(activeText)
                            } else if (deltaX < 0 && deltaY > 0) {
                                // Down-Left -> Analyze Tone
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                gestureAlert = "Gesture Triggered: Analyzing Tone! 🎭"
                                viewModel.analyzeTone(activeText)
                            } else {
                                // Down-Right -> Expand Templates
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                val expanded = viewModel.tryExpandAbbreviation(activeText)
                                if (expanded != activeText) {
                                    gestureAlert = "Gesture Triggered: Template Expanded! ⚡"
                                    if (inPlaygroundMode) {
                                        onPlaygroundTextChange(expanded)
                                    } else {
                                        currentInputConnection?.let { conn ->
                                            conn.deleteSurroundingText(activeText.length, 0)
                                            conn.commitText(expanded, 1)
                                        }
                                    }
                                } else {
                                    gestureAlert = "No abbreviations found to expand. ⚡"
                                }
                            }
                        } else if (abs(deltaX) > abs(deltaY) && abs(deltaX) > 120) {
                            if (deltaX > 0) {
                                gestureAlert = "Gesture: Space"
                                onKeyPress(" ")
                            } else {
                                gestureAlert = "Gesture: Backspace"
                                onDelete()
                            }
                        } else if (abs(deltaY) > abs(deltaX) && abs(deltaY) > 120) {
                            if (deltaY < 0) {
                                gestureAlert = "Gesture: Show AI Actions"
                                showAiActions = true
                            } else {
                                gestureAlert = "Gesture: Hide AI Actions"
                                showAiActions = false
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragX += dragAmount.x
                        dragY += dragAmount.y
                        
                        val dx = dragX
                        val dy = dragY
                        dragPreview = when {
                            abs(dx) > 100 && abs(dy) > 100 -> {
                                when {
                                    dx > 0 && dy < 0 -> "🌐 Release to Translate (Up-Right)"
                                    dx < 0 && dy < 0 -> "🔍 Release to Summarize (Up-Left)"
                                    dx < 0 && dy > 0 -> "🎭 Release to Analyze Tone (Down-Left)"
                                    else -> "⚡ Release to Expand Custom Shortcut (Down-Right)"
                                }
                            }
                            abs(dx) > abs(dy) && abs(dx) > 120 -> {
                                if (dx > 0) "␠ Release for Space (Right)" else "⌫ Release to Backspace (Left)"
                            }
                            abs(dy) > abs(dx) && abs(dy) > 120 -> {
                                if (dy < 0) "💡 Release to Show AI Panel (Up)" else "❌ Release to Collapse (Down)"
                            }
                            else -> "Drawing Gesture Trail..."
                        }
                    }
                )
            }
    ) {
        // --- LIVE GESTURE DRAWING TRAIL / PREVIEW OVERLAY ---
        if (dragPreview != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF6750A4))
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = dragPreview!!,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        // --- GESTURE INDICATOR ALERT OR TONE NOTIFICATION ---
        if (gestureAlert != null) {
            Text(
                text = gestureAlert ?: "",
                color = Color(0xFFD0BCFF),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
            // auto-dismiss gesture alert quickly
            androidx.compose.runtime.LaunchedEffect(gestureAlert) {
                kotlinx.coroutines.delay(1200)
                gestureAlert = null
            }
        }

        // --- AI RESULTS & INTERACTIVE SHELF BAR ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .background(Color(0xFFF7F2FA))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (isLoading) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color(0xFF6750A4))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Agentic AI processing...", color = Color(0xFF1C1B1F), fontSize = 12.sp)
                }
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (grammarCorrection != null) {
                        val correction = grammarCorrection!!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Corrected:", color = Color(0xFF15803D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = correction.corrected,
                                    color = Color(0xFF1C1B1F),
                                    fontSize = 14.sp,
                                    maxLines = 1
                                )
                            }
                            Button(
                                onClick = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    if (inPlaygroundMode) {
                                        onPlaygroundTextChange(correction.corrected)
                                    } else {
                                        currentInputConnection?.let { conn ->
                                            conn.deleteSurroundingText(activeText.length, 0)
                                            conn.commitText(correction.corrected, 1)
                                        }
                                    }
                                    viewModel.fixGrammar("") // reset
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                                modifier = Modifier.height(32.dp),
                                contentPadding = RowDefaultsButtonPadding
                            ) {
                                Text("Apply", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (toneAnalysis != null) {
                        val analysis = toneAnalysis!!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🎭 Tone: ${analysis.sentiment} (${(analysis.toneScore * 100).toInt()}%)",
                                color = Color(0xFF21005D),
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(analysis.suggestions) { tip ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFE8DEF8))
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text(tip, color = Color(0xFF21005D), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    } else if (summary != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Summary:", color = Color(0xFF7E22CE), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(summary!!, color = Color(0xFF1C1B1F), fontSize = 13.sp, maxLines = 1)
                            }
                            Button(
                                onClick = {
                                    if (inPlaygroundMode) {
                                        onPlaygroundTextChange(summary!!)
                                    } else {
                                        currentInputConnection?.let { conn ->
                                            conn.deleteSurroundingText(activeText.length, 0)
                                            conn.commitText(summary!!, 1)
                                        }
                                    }
                                    viewModel.summarizeMessage("") // reset
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                                modifier = Modifier.height(32.dp),
                                contentPadding = RowDefaultsButtonPadding
                            ) {
                                Text("Replace", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (translation != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Translated:", color = Color(0xFF0369A1), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(translation!!, color = Color(0xFF1C1B1F), fontSize = 13.sp, maxLines = 1)
                            }
                            Button(
                                onClick = {
                                    if (inPlaygroundMode) {
                                        onPlaygroundTextChange(translation!!)
                                    } else {
                                        currentInputConnection?.let { conn ->
                                            conn.deleteSurroundingText(activeText.length, 0)
                                            conn.commitText(translation!!, 1)
                                        }
                                    }
                                    viewModel.translateText("") // reset
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                                modifier = Modifier.height(32.dp),
                                contentPadding = RowDefaultsButtonPadding
                            ) {
                                Text("Insert", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (suggestions.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("💡 Replies:", color = Color(0xFF6750A4), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                items(suggestions) { reply ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(16.dp))
                                            .background(Color(0xFFE8DEF8))
                                            .clickable {
                                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                onKeyPress(reply)
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(reply, color = Color(0xFF21005D), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                        }
                    } else {
                        // Standard Active Input display with Predictive Autocomplete options!
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isOfflineMode) "🔒 Offline Privacy Active" else "🚀 Agentic Online Mode",
                                    color = if (isOfflineMode) Color(0xFF15803D) else Color(0xFF6750A4),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (liveSwipePreviewWord != null) {
                                    Text(
                                        text = "Swiping: ${liveSwipePreviewWord!!} ✍️",
                                        color = Color(0xFF6750A4),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    )
                                } else if (predictiveSuggestions.isNotEmpty()) {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                                    ) {
                                        items(predictiveSuggestions) { suggestion ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(Color(0xFFEADDFF))
                                                    .clickable {
                                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                                        val words = activeText.split("\\s+".toRegex()).toMutableList()
                                                        if (words.isNotEmpty()) {
                                                            words[words.size - 1] = suggestion
                                                        } else {
                                                            words.add(suggestion)
                                                        }
                                                        val newText = words.joinToString(" ") + " "
                                                        if (inPlaygroundMode) {
                                                            onPlaygroundTextChange(newText)
                                                        } else {
                                                            currentInputConnection?.let { conn ->
                                                                val lastWordLength = activeText.split("\\s+".toRegex()).lastOrNull()?.length ?: 0
                                                                conn.deleteSurroundingText(lastWordLength, 0)
                                                                conn.commitText(suggestion + " ", 1)
                                                            }
                                                        }
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                                            ) {
                                                Text(suggestion, color = Color(0xFF21005D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                } else {
                                    Text(
                                        text = if (activeText.isEmpty()) "Start typing, swipe, or use AI tools below..." else activeText,
                                        color = if (activeText.isEmpty()) Color.Gray else Color(0xFF1C1B1F),
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { 
                                        isSwipeToTypeEnabled = !isSwipeToTypeEnabled 
                                        gestureAlert = if (isSwipeToTypeEnabled) "Swipe-to-Type Enabled ✍️" else "Standard Typing Enabled ⌨️"
                                    },
                                    modifier = Modifier.size(36.dp).testTag("toggle_swipe")
                                ) {
                                    Text(if (isSwipeToTypeEnabled) "✍️" else "⌨️", fontSize = 16.sp)
                                }
                                IconButton(
                                    onClick = { showGestureGuide = !showGestureGuide },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Text("❓", fontSize = 14.sp)
                                }
                                if (activeText.isNotEmpty()) {
                                    IconButton(
                                        onClick = { viewModel.suggestReplies(activeText) },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Info,
                                            contentDescription = "Smart Reply",
                                            tint = Color(0xFF6750A4)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- GESTURE GUIDE CHEAT SHEET PANEL ---
        AnimatedVisibility(
            visible = showGestureGuide,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📐 Gestures Cheat Sheet",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF6750A4)
                        )
                        IconButton(
                            onClick = { showGestureGuide = false },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Close",
                                tint = Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column {
                            Text("↖ Swipe Up-Left: Summarize", fontSize = 11.sp, color = Color.DarkGray)
                            Text("↙ Swipe Down-Left: Analyze Tone", fontSize = 11.sp, color = Color.DarkGray)
                            Text("← Swipe Left: Backspace", fontSize = 11.sp, color = Color.DarkGray)
                        }
                        Column {
                            Text("↗ Swipe Up-Right: Translate", fontSize = 11.sp, color = Color.DarkGray)
                            Text("↘ Swipe Down-Right: Expand Shortcut", fontSize = 11.sp, color = Color.DarkGray)
                            Text("→ Swipe Right: Space", fontSize = 11.sp, color = Color.DarkGray)
                        }
                    }
                }
            }
        }

        // --- AI ACTIONS BUTTONS ROW ---
        AnimatedVisibility(
            visible = showAiActions,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F2FA))
                    .padding(vertical = 4.dp, horizontal = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Fix Grammar Button
                AiActionButton(
                    label = "Fix Grammar",
                    icon = "📝",
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        viewModel.fixGrammar(activeText)
                    },
                    modifier = Modifier.testTag("action_grammar")
                )

                // Summarize Button
                AiActionButton(
                    label = "Summarize",
                    icon = "🔍",
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        viewModel.summarizeMessage(activeText)
                    },
                    modifier = Modifier.testTag("action_summarize")
                )

                // Translate Button
                AiActionButton(
                    label = "Translate",
                    icon = "🌐",
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        viewModel.translateText(activeText)
                    },
                    modifier = Modifier.testTag("action_translate")
                )

                // Distill Sentiment/Tone Button
                AiActionButton(
                    label = "Analyze Tone",
                    icon = "🎭",
                    onClick = {
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        viewModel.analyzeTone(activeText)
                    },
                    modifier = Modifier.testTag("action_tone")
                )

                IconButton(
                    onClick = { showAiActions = false },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Collapse",
                        tint = Color.Gray
                    )
                }
            }
        }

        if (!showAiActions) {
            IconButton(
                onClick = { showAiActions = true },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .height(20.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowUp,
                    contentDescription = "Expand AI actions",
                    tint = Color(0xFFD0BCFF)
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- KEYBOARD KEYS MATRIX ---
        val qwertyRows = if (isNumberMode) {
            listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
                listOf("*", "\"", "'", ":", ";", "!", "?", "\\", "%", "=")
            )
        } else {
            listOf(
                listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
                listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
                listOf("z", "x", "c", "v", "b", "n", "m")
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    keysAreaWidth = size.width.toFloat()
                    keysAreaHeight = size.height.toFloat()
                }
                .pointerInput(isSwipeToTypeEnabled, isNumberMode) {
                    if (isSwipeToTypeEnabled && !isNumberMode) {
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                swipePathPoints.clear()
                                if (keysAreaWidth > 0 && keysAreaHeight > 0) {
                                    val relX = (startOffset.x / keysAreaWidth) * 10f
                                    val relY = (startOffset.y / keysAreaHeight) * 3f
                                    swipePathPoints.add(com.example.util.SwipePoint(relX, relY))
                                }
                            },
                            onDragEnd = {
                                if (swipePathPoints.size > 1) {
                                    val matches = com.example.util.SwipeToTypeEngine.getSwipeWordMatches(
                                        swipePathPoints.toList(),
                                        userVocabulary = topVocabulary.map { it.word }
                                    )
                                    val finalWord = matches.firstOrNull()
                                    if (finalWord != null) {
                                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                        val typedText = if (isCapsLock) finalWord.uppercase() else finalWord
                                        onKeyPress(typedText)
                                        onKeyPress(" ")
                                        gestureAlert = "Swiped: $typedText ✨"
                                    } else {
                                        gestureAlert = "Pattern not recognized ⌨️"
                                    }
                                }
                                swipePathPoints.clear()
                                liveSwipePreviewWord = null
                            },
                            onDragCancel = {
                                swipePathPoints.clear()
                                liveSwipePreviewWord = null
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (keysAreaWidth > 0 && keysAreaHeight > 0) {
                                    val relX = (change.position.x / keysAreaWidth) * 10f
                                    val relY = (change.position.y / keysAreaHeight) * 3f
                                    
                                    val lastPt = swipePathPoints.lastOrNull()
                                    if (lastPt == null || abs(lastPt.x - relX) > 0.05f || abs(lastPt.y - relY) > 0.05f) {
                                        swipePathPoints.add(com.example.util.SwipePoint(relX, relY))
                                    }
                                    
                                    if (swipePathPoints.size > 1) {
                                        val matches = com.example.util.SwipeToTypeEngine.getSwipeWordMatches(
                                            swipePathPoints.toList(),
                                            userVocabulary = topVocabulary.map { it.word }
                                        )
                                        liveSwipePreviewWord = matches.firstOrNull()
                                    }
                                }
                            }
                        )
                    }
                }
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Render QWERTY Rows
                for (rowIndex in qwertyRows.indices) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        val currentRow = qwertyRows[rowIndex]

                        // Row 3 Shift Key
                        if (!isNumberMode && rowIndex == 2) {
                            KeyButton(
                                text = if (isCapsLock) "▲" else "▲",
                                onClick = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    isCapsLock = !isCapsLock
                                },
                                isSpecial = true,
                                modifier = Modifier
                                    .width(44.dp)
                                    .testTag("key_shift")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        // Standard characters
                        for (char in currentRow) {
                            val keyText = if (isCapsLock && !isNumberMode) char.uppercase() else char
                            KeyButton(
                                text = keyText,
                                onClick = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                                    onKeyPress(keyText)
                                },
                                modifier = Modifier.testTag("key_$char")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        // Row 3 Backspace Key
                        if (!isNumberMode && rowIndex == 2) {
                            KeyButton(
                                text = "⌫",
                                onClick = {
                                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    onDelete()
                                },
                                isSpecial = true,
                                modifier = Modifier
                                    .width(44.dp)
                                    .testTag("key_backspace")
                            )
                        }
                    }
                }
            }

            // Visual trailing gesture path canvas overlay
            if (isSwipeToTypeEnabled && swipePathPoints.size > 1) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val path = androidx.compose.ui.graphics.Path()
                    val firstPt = swipePathPoints.first()
                    val startX = (firstPt.x / 10f) * size.width
                    val startY = (firstPt.y / 3f) * size.height
                    path.moveTo(startX, startY)
                    
                    for (i in 1 until swipePathPoints.size) {
                        val pt = swipePathPoints[i]
                        val px = (pt.x / 10f) * size.width
                        val py = (pt.y / 3f) * size.height
                        path.lineTo(px, py)
                    }
                    
                    drawPath(
                        path = path,
                        color = Color(0xFF6750A4).copy(alpha = 0.5f),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(
                            width = 6.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round
                        )
                    )
                }
            }
        }


        // Row 4: bottom command bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mode key (ABC / 123)
            KeyButton(
                text = if (isNumberMode) "ABC" else "?123",
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    isNumberMode = !isNumberMode
                },
                isSpecial = true,
                modifier = Modifier
                    .width(56.dp)
                    .testTag("key_mode")
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Global/Local Privacy Mode Fast Switch
            KeyButton(
                text = if (isOfflineMode) "🔒 Lcl" else "🚀 Cld",
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    viewModel.toggleOfflineMode()
                    gestureAlert = if (isOfflineMode) "Local Privacy Mode Active" else "Cloud AI Mode Enabled"
                },
                isSpecial = true,
                modifier = Modifier
                    .width(56.dp)
                    .testTag("key_privacy_toggle")
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Space Key
            KeyButton(
                text = "Space",
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onKeyPress(" ")
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("key_space")
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Space . Key
            KeyButton(
                text = ".",
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                    onKeyPress(".")
                },
                modifier = Modifier
                    .width(36.dp)
                    .testTag("key_period")
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Action/Enter Key
            KeyButton(
                text = "Enter",
                onClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onAction()
                },
                isSpecial = true,
                modifier = Modifier
                    .width(56.dp)
                    .testTag("key_enter")
            )
        }
    }
}

@Composable
fun AiActionButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isGrammar = label == "Fix Grammar"
    val containerColor = if (isGrammar) Color(0xFFEADDFF) else Color.White
    val borderColor = if (isGrammar) Color(0xFFD0BCFF) else Color(0xFFCBD5E1)
    val contentColor = if (isGrammar) Color(0xFF21005D) else Color(0xFF1C1B1F)

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .clickable { onClick() },
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(icon, fontSize = 14.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(label, color = contentColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun KeyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSpecial: Boolean = false
) {
    val isEnter = text == "Enter"
    val containerColor = when {
        isEnter -> Color(0xFF6750A4)
        isSpecial -> Color(0xFFE7E0EC)
        else -> Color.White
    }
    val contentColor = if (isEnter) Color.White else Color(0xFF1C1B1F)

    Box(
        modifier = modifier
            .size(width = 32.dp, height = 44.dp)
            .shadow(1.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

val RowDefaultsButtonPadding = androidx.compose.foundation.layout.PaddingValues(
    horizontal = 12.dp,
    vertical = 0.dp
)
