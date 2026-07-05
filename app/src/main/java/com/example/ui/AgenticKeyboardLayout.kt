package com.example.ui

import android.view.inputmethod.InputConnection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.example.util.CommandPalette
import com.example.util.ReplyIntents
import com.example.util.SwipePoint
import com.example.util.SwipeToTypeEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Shift key behavior: one-shot shift resets after a letter; double-tap locks caps. */
enum class ShiftState { OFF, SHIFT, CAPS_LOCK }

/** Long-press alternatives for accent characters and common punctuation. */
private val keyVariants = mapOf(
    "a" to listOf("à", "á", "â", "ä", "ã", "å"),
    "e" to listOf("è", "é", "ê", "ë"),
    "i" to listOf("ì", "í", "î", "ï"),
    "o" to listOf("ò", "ó", "ô", "ö", "õ"),
    "u" to listOf("ù", "ú", "û", "ü"),
    "n" to listOf("ñ"),
    "c" to listOf("ç"),
    "s" to listOf("ß"),
    "y" to listOf("ý", "ÿ"),
    "." to listOf(",", "!", "?", ";", ":", "…")
)

private val SENTENCE_ENDINGS = setOf('.', '!', '?')

@Composable
fun AgenticKeyboardLayout(
    viewModel: KeyboardViewModel,
    modifier: Modifier = Modifier,
    onKeyPress: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onAction: () -> Unit = {},
    onMicPress: () -> Unit = {},
    onCursorMove: (Int) -> Unit = {},
    inputConnectionProvider: () -> InputConnection? = { null },
    inPlaygroundMode: Boolean = false,
    playgroundTextState: String = "",
    onPlaygroundTextChange: (String) -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val clipboardManager = LocalClipboardManager.current
    var shiftState by remember { mutableStateOf(ShiftState.OFF) }
    var lastShiftTapTime by remember { mutableLongStateOf(0L) }
    var lastSpaceTime by remember { mutableLongStateOf(0L) }
    var isNumberMode by remember { mutableStateOf(false) }

    // Collect states from ViewModel
    val isLoading by viewModel.isLoading.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val grammarCorrection by viewModel.grammarCorrection.collectAsState()
    val toneAnalysis by viewModel.toneAnalysis.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val translation by viewModel.translation.collectAsState()
    val rewrite by viewModel.rewrite.collectAsState()
    val composeResult by viewModel.composeResult.collectAsState()
    val explanation by viewModel.explanation.collectAsState()
    val continuation by viewModel.continuation.collectAsState()
    val proofreadHint by viewModel.proofreadHint.collectAsState()
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val predictiveSuggestions by viewModel.predictiveSuggestions.collectAsState()
    val topVocabulary by viewModel.topVocabulary.collectAsState()
    val trackedInputText by viewModel.inputText.collectAsState()
    val isSensitiveField by viewModel.isSensitiveField.collectAsState()
    val isSwipeToTypeEnabled by viewModel.isSwipeEnabled.collectAsState()
    val isAutoCapitalizeEnabled by viewModel.isAutoCapitalizeEnabled.collectAsState()
    val isNumberRowEnabled by viewModel.isNumberRowEnabled.collectAsState()
    val isHapticsEnabled by viewModel.isHapticsEnabled.collectAsState()
    val isLearningPaused by viewModel.isLearningPaused.collectAsState()
    val replyIntentContext by viewModel.replyIntentContext.collectAsState()
    val sendGuardWarning by viewModel.sendGuardWarning.collectAsState()
    val customCommands by viewModel.customCommands.collectAsState()

    fun buzz(type: HapticFeedbackType) {
        if (isHapticsEnabled) haptic.performHapticFeedback(type)
    }

    // Swipe gesture metrics
    var dragX by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var gestureAlert by remember { mutableStateOf<String?>(null) }
    var dragPreview by remember { mutableStateOf<String?>(null) }
    var showGestureGuide by remember { mutableStateOf(false) }
    var showClipboardActions by remember { mutableStateOf(false) }
    var clipboardText by remember { mutableStateOf<String?>(null) }

    // Swipe-to-Type Gesture States
    var keysAreaWidth by remember { mutableFloatStateOf(0f) }
    var keysAreaHeight by remember { mutableFloatStateOf(0f) }
    val swipePathPoints = remember { mutableStateListOf<SwipePoint>() }
    var liveSwipePreviewWord by remember { mutableStateOf<String?>(null) }

    // Text shown during composition. In IME mode the service keeps the ViewModel in
    // sync through onUpdateSelection, so this recomposes as the user types.
    val activeText = if (inPlaygroundMode) playgroundTextState else trackedInputText

    // Fresh editor text for event handlers: reading the InputConnection at event
    // time avoids acting on a stale snapshot captured during composition.
    fun currentText(): String = if (inPlaygroundMode) {
        playgroundTextState
    } else {
        inputConnectionProvider()?.getTextBeforeCursor(1000, 0)?.toString() ?: trackedInputText
    }

    // Text the user has selected in the active editor, or null when nothing is
    // selected. Playground mode has no editor selection to read.
    fun selectedText(): String? = if (inPlaygroundMode) {
        null
    } else {
        inputConnectionProvider()?.getSelectedText(0)?.toString()?.takeIf { it.isNotBlank() }
    }

    // Source text for AI actions: the selection when one exists, else the draft.
    fun aiSourceText(): String = selectedText() ?: currentText()

    /** True when the caret sits at a position that should auto-capitalize. */
    fun isSentenceStart(text: String): Boolean {
        if (text.isEmpty() || text.endsWith("\n")) return true
        if (!text.last().isWhitespace()) return false
        val lastVisible = text.trimEnd().lastOrNull() ?: return true
        return lastVisible in SENTENCE_ENDINGS
    }

    // Uppercase rendering combines explicit shift with auto-capitalization.
    val autoCapActive = isAutoCapitalizeEnabled && !isNumberMode &&
        shiftState == ShiftState.OFF && isSentenceStart(activeText)
    val shiftActive = shiftState != ShiftState.OFF || autoCapActive

    /**
     * Replaces the active selection with [newText] when one exists, otherwise
     * replaces everything before the cursor. commitText on its own already
     * replaces a selection, so only the no-selection case needs a delete first.
     */
    fun replaceActiveText(newText: String) {
        if (inPlaygroundMode) {
            onPlaygroundTextChange(newText)
        } else {
            inputConnectionProvider()?.let { conn ->
                if (conn.getSelectedText(0).isNullOrEmpty()) {
                    conn.deleteSurroundingText(currentText().length, 0)
                }
                conn.commitText(newText, 1)
            }
        }
    }

    /**
     * Smart space: double-tap inserts ". ", a committed word is expanded from
     * shortcut templates or auto-corrected from learned typo rules (revertible
     * with backspace), and every committed word feeds on-device learning.
     */
    fun handleSpace() {
        val text = currentText()
        val now = System.currentTimeMillis()

        if (now - lastSpaceTime < 400 && text.endsWith(" ") && text.trimEnd().lastOrNull()?.isLetterOrDigit() == true) {
            lastSpaceTime = 0L
            if (inPlaygroundMode) {
                onPlaygroundTextChange(text.dropLast(1) + ". ")
            } else {
                inputConnectionProvider()?.let { conn ->
                    conn.deleteSurroundingText(1, 0)
                    conn.commitText(". ", 1)
                }
            }
            gestureAlert = "Period inserted ✏️"
            return
        }
        lastSpaceTime = now

        val lastWord = text.takeLastWhile { !it.isWhitespace() }
        if (lastWord.isNotEmpty()) {
            viewModel.onWordCommitted(lastWord)
            val replacement = viewModel.resolveWordCommit(lastWord)
            if (replacement != null && replacement.replacement != lastWord) {
                if (inPlaygroundMode) {
                    onPlaygroundTextChange(text.dropLast(lastWord.length) + replacement.replacement + " ")
                } else {
                    inputConnectionProvider()?.let { conn ->
                        conn.deleteSurroundingText(lastWord.length, 0)
                        conn.commitText("${replacement.replacement} ", 1)
                    }
                }
                viewModel.registerAutoCorrection(lastWord, replacement.replacement, replacement.fromLearnedRule)
                if (replacement.fromLearnedRule) {
                    viewModel.recordAutoCorrectionStat()
                } else {
                    viewModel.recordShortcutExpansionStat()
                }
                gestureAlert = "✨ $lastWord → ${replacement.replacement} (⌫ undoes)"
                return
            }
        }
        onKeyPress(" ")
    }

    /**
     * Backspace with auto-correction undo: pressed right after a smart-space
     * replacement it restores what the user actually typed; reverting the same
     * learned rule twice deletes that rule.
     */
    fun handleBackspace() {
        val pending = viewModel.peekPendingUndo()
        val text = currentText()
        if (pending != null && text.endsWith(pending.replacement + " ")) {
            val restored = pending.original + " "
            if (inPlaygroundMode) {
                onPlaygroundTextChange(text.dropLast(pending.replacement.length + 1) + restored)
            } else {
                inputConnectionProvider()?.let { conn ->
                    conn.deleteSurroundingText(pending.replacement.length + 1, 0)
                    conn.commitText(restored, 1)
                }
            }
            viewModel.onUndoApplied()
            gestureAlert = "Reverted to \"${pending.original}\""
        } else {
            viewModel.clearPendingUndo()
            onDelete()
        }
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
                                buzz(HapticFeedbackType.LongPress)
                                gestureAlert = "Gesture Triggered: Translating! 🌐"
                                viewModel.translateText(aiSourceText())
                            } else if (deltaX < 0 && deltaY < 0) {
                                // Up-Left -> Summarize
                                buzz(HapticFeedbackType.LongPress)
                                gestureAlert = "Gesture Triggered: Summarizing! 🔍"
                                viewModel.summarizeMessage(aiSourceText())
                            } else if (deltaX < 0 && deltaY > 0) {
                                // Down-Left -> Analyze Tone
                                buzz(HapticFeedbackType.LongPress)
                                gestureAlert = "Gesture Triggered: Analyzing Tone! 🎭"
                                viewModel.analyzeTone(aiSourceText())
                            } else {
                                // Down-Right -> Expand Templates
                                buzz(HapticFeedbackType.LongPress)
                                val text = currentText()
                                val expanded = viewModel.tryExpandAbbreviation(text)
                                if (expanded != text) {
                                    gestureAlert = "Gesture Triggered: Template Expanded! ⚡"
                                    viewModel.recordShortcutExpansionStat()
                                    replaceActiveText(expanded)
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
                delay(1500)
                gestureAlert = null
            }
        }

        // --- AI RESULTS & INTERACTIVE SHELF BAR ---
        val hasAiResult = grammarCorrection != null || toneAnalysis != null || summary != null ||
            translation != null || rewrite != null || composeResult != null ||
            explanation != null || continuation != null || suggestions.isNotEmpty() ||
            sendGuardWarning != null

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                    if (sendGuardWarning != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("⚠️ This might land harshly", color = Color(0xFFB3261E), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Keep editing, or send it as-is.", color = Color(0xFF5F5D6B), fontSize = 10.sp, maxLines = 1)
                            }
                            ClipActionChip("✏️ Revise") {
                                buzz(HapticFeedbackType.TextHandleMove)
                                viewModel.dismissSendGuardWarning()
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(
                                onClick = {
                                    buzz(HapticFeedbackType.LongPress)
                                    // interceptSend sees the armed warning and lets this one through
                                    onAction()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E)),
                                modifier = Modifier.height(32.dp).testTag("send_anyway_button"),
                                contentPadding = RowDefaultsButtonPadding
                            ) {
                                Text("Send anyway", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (grammarCorrection != null) {
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
                                    buzz(HapticFeedbackType.LongPress)
                                    replaceActiveText(correction.corrected)
                                    viewModel.recordAiApplyStat()
                                    viewModel.dismissResults()
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
                            Column {
                                Text(
                                    text = "🎭 ${analysis.sentiment} (${(analysis.toneScore * 100).toInt()}%)",
                                    color = Color(0xFF21005D),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                analysis.note?.let { note ->
                                    Text(note, color = Color(0xFF5F5D6B), fontSize = 9.sp, maxLines = 1)
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                // Compact writing-quality meter chips
                                val meterChips = listOfNotNull(
                                    analysis.clarity?.let { "🔍 $it" },
                                    analysis.warmth?.let { "🤝 $it" },
                                    analysis.firmness?.let { "💪 $it" },
                                    analysis.risk?.let { "⚠️ Risk $it" },
                                    analysis.lengthLabel?.let { "📏 $it" }
                                )
                                items(meterChips) { chip ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFD0BCFF))
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(chip, color = Color(0xFF21005D), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                                // Tone-matched emoji, insertable with a tap
                                items(viewModel.emojisForSentiment(analysis.sentiment)) { emoji ->
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(Color(0xFFFFD8E4))
                                            .clickable {
                                                buzz(HapticFeedbackType.TextHandleMove)
                                                onKeyPress(emoji)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        Text(emoji, fontSize = 13.sp)
                                    }
                                }
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
                                    replaceActiveText(summary!!)
                                    viewModel.recordAiApplyStat()
                                    viewModel.dismissResults()
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
                                    replaceActiveText(translation!!)
                                    viewModel.recordAiApplyStat()
                                    viewModel.dismissResults()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                                modifier = Modifier.height(32.dp),
                                contentPadding = RowDefaultsButtonPadding
                            ) {
                                Text("Insert", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (rewrite != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Rewritten (${viewModel.effectivePersona()}):", color = Color(0xFFB45309), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(rewrite!!, color = Color(0xFF1C1B1F), fontSize = 13.sp, maxLines = 1)
                            }
                            Button(
                                onClick = {
                                    buzz(HapticFeedbackType.LongPress)
                                    replaceActiveText(rewrite!!)
                                    viewModel.recordAiApplyStat()
                                    viewModel.dismissResults()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                                modifier = Modifier.height(32.dp),
                                contentPadding = RowDefaultsButtonPadding
                            ) {
                                Text("Apply", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (composeResult != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Composed draft:", color = Color(0xFF9333EA), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(composeResult!!, color = Color(0xFF1C1B1F), fontSize = 13.sp, maxLines = 1)
                            }
                            Button(
                                onClick = {
                                    buzz(HapticFeedbackType.LongPress)
                                    replaceActiveText(composeResult!!)
                                    viewModel.recordAiApplyStat()
                                    viewModel.dismissResults()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                                modifier = Modifier.height(32.dp),
                                contentPadding = RowDefaultsButtonPadding
                            ) {
                                Text("Use", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (continuation != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Continue with:", color = Color(0xFF0E7490), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text(continuation!!, color = Color(0xFF1C1B1F), fontSize = 13.sp, maxLines = 1)
                            }
                            Button(
                                onClick = {
                                    buzz(HapticFeedbackType.LongPress)
                                    val text = currentText()
                                    val separator = if (text.isEmpty() || text.last().isWhitespace()) "" else " "
                                    onKeyPress(separator + continuation!!)
                                    viewModel.recordAiApplyStat()
                                    viewModel.dismissResults()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                                modifier = Modifier.height(32.dp),
                                contentPadding = RowDefaultsButtonPadding
                            ) {
                                Text("Append", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (explanation != null) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text("Plain-language explanation:", color = Color(0xFF15803D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(explanation!!, color = Color(0xFF1C1B1F), fontSize = 12.sp, maxLines = 2)
                        }
                    } else if (suggestions.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("💡", fontSize = 12.sp, modifier = Modifier.padding(end = 4.dp))
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
                                                buzz(HapticFeedbackType.TextHandleMove)
                                                onKeyPress(reply)
                                                viewModel.recordAiApplyStat()
                                                viewModel.dismissResults()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(reply, color = Color(0xFF21005D), fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
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
                                    text = when {
                                        isSensitiveField -> "🔒 Secure field — AI & learning disabled"
                                        isLearningPaused -> "🕶 Learning paused"
                                        isOfflineMode -> "🔒 Offline Privacy Active"
                                        else -> "🚀 Agentic Online Mode"
                                    },
                                    color = if (isSensitiveField || isOfflineMode) Color(0xFF15803D) else Color(0xFF6750A4),
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
                                } else if (proofreadHint != null && !isSensitiveField) {
                                    Box(
                                        modifier = Modifier
                                            .padding(top = 2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFFDCFCE7))
                                            .clickable { viewModel.promoteProofreadHint() }
                                            .padding(horizontal = 10.dp, vertical = 4.dp)
                                            .testTag("proofread_hint")
                                    ) {
                                        Text(
                                            "📝 ${proofreadHint!!.correctionsCount} fix(es) found — tap to review",
                                            color = Color(0xFF15803D),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                } else if (predictiveSuggestions.isNotEmpty() && !isSensitiveField) {
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
                                                        buzz(HapticFeedbackType.TextHandleMove)
                                                        val text = currentText()
                                                        val lastWord = text.takeLastWhile { !it.isWhitespace() }
                                                        if (inPlaygroundMode) {
                                                            onPlaygroundTextChange(text.dropLast(lastWord.length) + suggestion + " ")
                                                        } else {
                                                            inputConnectionProvider()?.let { conn ->
                                                                conn.deleteSurroundingText(lastWord.length, 0)
                                                                conn.commitText("$suggestion ", 1)
                                                            }
                                                        }
                                                        viewModel.onWordCommitted(suggestion)
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
                                        viewModel.setSwipeEnabled(!isSwipeToTypeEnabled)
                                        gestureAlert = if (!isSwipeToTypeEnabled) "Swipe-to-Type Enabled ✍️" else "Standard Typing Enabled ⌨️"
                                    },
                                    modifier = Modifier.size(36.dp).testTag("toggle_swipe")
                                ) {
                                    Text(if (isSwipeToTypeEnabled) "✍️" else "⌨️", fontSize = 16.sp)
                                }
                                if (!isSensitiveField) {
                                    IconButton(
                                        onClick = {
                                            val clip = clipboardManager.getText()?.text
                                            if (clip.isNullOrBlank()) {
                                                gestureAlert = "Clipboard is empty 📋"
                                            } else {
                                                clipboardText = clip
                                                showClipboardActions = !showClipboardActions
                                            }
                                        },
                                        modifier = Modifier.size(36.dp).testTag("clipboard_actions")
                                    ) {
                                        Text("📋", fontSize = 14.sp)
                                    }
                                }
                                IconButton(
                                    onClick = { showGestureGuide = !showGestureGuide },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Text("❓", fontSize = 14.sp)
                                }
                            }
                        }
                    }
                    }
                    if (hasAiResult) {
                        IconButton(
                            onClick = {
                                buzz(HapticFeedbackType.TextHandleMove)
                                viewModel.regenerate()
                            },
                            modifier = Modifier.size(28.dp).testTag("regenerate_result")
                        ) {
                            Text("↻", color = Color(0xFF6750A4), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = { viewModel.dismissResults() },
                            modifier = Modifier.size(28.dp).testTag("dismiss_results")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss AI result",
                                tint = Color.Gray,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // --- ITERATE CHIPS (refine the AI text result that is showing) ---
        val hasRefinableResult = grammarCorrection != null || summary != null || translation != null ||
            rewrite != null || composeResult != null || continuation != null
        AnimatedVisibility(
            visible = hasRefinableResult && !isLoading,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F2FA))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .horizontalScroll(rememberScrollState())
                    .testTag("iterate_chips"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KeyboardViewModel.RESULT_REFINEMENTS.keys.forEach { adjustment ->
                    ClipActionChip(adjustment) {
                        buzz(HapticFeedbackType.TextHandleMove)
                        viewModel.refineResult(adjustment)
                    }
                }
            }
        }

        // --- CLIPBOARD INTELLIGENCE PANEL ---
        AnimatedVisibility(
            visible = showClipboardActions && clipboardText != null && !isSensitiveField,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "📋 " + (clipboardText ?: "").take(60).replace("\n", " ") +
                            if ((clipboardText ?: "").length > 60) "…" else "",
                        color = Color(0xFF49454F),
                        fontSize = 11.sp,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ClipActionChip("Paste") {
                            onKeyPress(clipboardText ?: "")
                            showClipboardActions = false
                        }
                        ClipActionChip("Reply Ideas 💡") {
                            viewModel.requestReplyIdeas(clipboardText ?: "")
                            showClipboardActions = false
                        }
                        ClipActionChip("Translate 🌐") {
                            viewModel.translateText(clipboardText ?: "")
                            showClipboardActions = false
                        }
                        ClipActionChip("Summarize 🔍") {
                            viewModel.summarizeMessage(clipboardText ?: "")
                            showClipboardActions = false
                        }
                        ClipActionChip("Explain 🧠") {
                            viewModel.explainText(clipboardText ?: "")
                            showClipboardActions = false
                        }
                    }
                }
            }
        }

        // --- REPLY INTENT CHIPS PANEL ---
        // Shown after "Reply Ideas" is tapped: the user picks the direction the
        // generated replies should take before anything is sent to the model.
        AnimatedVisibility(
            visible = replyIntentContext != null && !isSensitiveField,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF3EDF7)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Reply with which intent?",
                        color = Color(0xFF49454F),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .testTag("reply_intent_chips"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ClipActionChip("✨ Any") {
                            buzz(HapticFeedbackType.TextHandleMove)
                            viewModel.chooseReplyIntent(null)
                        }
                        ReplyIntents.ALL.forEach { intent ->
                            ClipActionChip(intent) {
                                buzz(HapticFeedbackType.TextHandleMove)
                                viewModel.chooseReplyIntent(intent)
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
                            Text("␣␣ Double Space: Period", fontSize = 11.sp, color = Color.DarkGray)
                            Text("⌫ After auto-fix: Undo", fontSize = 11.sp, color = Color.DarkGray)
                        }
                        Column {
                            Text("↗ Swipe Up-Right: Translate", fontSize = 11.sp, color = Color.DarkGray)
                            Text("↘ Swipe Down-Right: Expand Shortcut", fontSize = 11.sp, color = Color.DarkGray)
                            Text("→ Swipe Right: Space", fontSize = 11.sp, color = Color.DarkGray)
                            Text("⇧⇧ Double Shift: Caps Lock", fontSize = 11.sp, color = Color.DarkGray)
                            Text("␣ Slide on Space: Move cursor", fontSize = 11.sp, color = Color.DarkGray)
                        }
                    }
                }
            }
        }

        // --- COMMAND PALETTE (draft starts with a "/" token) ---
        val paletteCommands = if (isSensitiveField) emptyList() else CommandPalette.matches(
            activeText,
            customCommands.map { CommandPalette.Command(it.token, "Custom", CommandPalette.Action.REWRITE, it.instruction) }
        )
        AnimatedVisibility(
            visible = paletteCommands.isNotEmpty(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEADDFF))
                    .padding(vertical = 4.dp, horizontal = 6.dp)
                    .horizontalScroll(rememberScrollState())
                    .testTag("command_palette"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                paletteCommands.forEach { cmd ->
                    ClipActionChip("${cmd.token} ${cmd.label}") {
                        buzz(HapticFeedbackType.TextHandleMove)
                        val body = CommandPalette.stripToken(currentText())
                        if (body.isBlank()) {
                            gestureAlert = "Type your text after ${cmd.token} first ⌨️"
                        } else {
                            replaceActiveText(body)
                            when (cmd.action) {
                                CommandPalette.Action.REWRITE -> viewModel.rewriteWithStyle(body, cmd.instruction)
                                CommandPalette.Action.PROOFREAD -> viewModel.fixGrammar(body)
                                CommandPalette.Action.TRANSLATE -> viewModel.translateText(body)
                            }
                        }
                    }
                }
            }
        }

        // --- AI ACTIONS BUTTONS ROW ---
        AnimatedVisibility(
            visible = showAiActions && !isSensitiveField,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF7F2FA))
                    .padding(vertical = 4.dp, horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AiActionButton(
                        label = "Fix Grammar",
                        icon = "📝",
                        highlighted = true,
                        onClick = {
                            buzz(HapticFeedbackType.TextHandleMove)
                            viewModel.fixGrammar(aiSourceText())
                        },
                        modifier = Modifier.testTag("action_grammar")
                    )

                    AiActionButton(
                        label = "Compose",
                        icon = "🪄",
                        onClick = {
                            buzz(HapticFeedbackType.TextHandleMove)
                            val text = aiSourceText()
                            if (text.isBlank()) {
                                gestureAlert = "Type an instruction first, e.g. \"tell her I'm 20 min late\" 🪄"
                            } else {
                                viewModel.composeFromInstruction(text)
                            }
                        },
                        modifier = Modifier.testTag("action_compose")
                    )

                    AiActionButton(
                        label = "Rewrite",
                        icon = "✨",
                        onClick = {
                            buzz(HapticFeedbackType.TextHandleMove)
                            viewModel.rewriteTone(aiSourceText())
                        },
                        onLongPress = {
                            buzz(HapticFeedbackType.LongPress)
                            val next = viewModel.cyclePersona()
                            gestureAlert = "Persona: $next 🎭"
                        },
                        modifier = Modifier.testTag("action_rewrite")
                    )

                    AiActionButton(
                        label = "Continue",
                        icon = "✒️",
                        onClick = {
                            buzz(HapticFeedbackType.TextHandleMove)
                            viewModel.continueDraft(currentText())
                        },
                        modifier = Modifier.testTag("action_continue")
                    )

                    AiActionButton(
                        label = "Summarize",
                        icon = "🔍",
                        onClick = {
                            buzz(HapticFeedbackType.TextHandleMove)
                            viewModel.summarizeMessage(aiSourceText())
                        },
                        modifier = Modifier.testTag("action_summarize")
                    )

                    AiActionButton(
                        label = "Translate",
                        icon = "🌐",
                        onClick = {
                            buzz(HapticFeedbackType.TextHandleMove)
                            viewModel.translateText(aiSourceText())
                        },
                        modifier = Modifier.testTag("action_translate")
                    )

                    AiActionButton(
                        label = "Analyze Tone",
                        icon = "🎭",
                        onClick = {
                            buzz(HapticFeedbackType.TextHandleMove)
                            viewModel.analyzeTone(aiSourceText())
                        },
                        modifier = Modifier.testTag("action_tone")
                    )
                }

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

        if (!showAiActions && !isSensitiveField) {
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
        val digitRow = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val qwertyRows = if (isNumberMode) {
            listOf(
                digitRow,
                listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
                listOf("*", "\"", "'", ":", ";", "!", "?", "\\", "%", "=")
            )
        } else {
            buildList {
                if (isNumberRowEnabled) add(digitRow)
                add(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"))
                add(listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"))
                add(listOf("z", "x", "c", "v", "b", "n", "m"))
            }
        }
        val bottomLetterRowIndex = qwertyRows.size - 1

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    keysAreaWidth = size.width.toFloat()
                    keysAreaHeight = size.height.toFloat()
                }
                .pointerInput(isSwipeToTypeEnabled, isNumberMode, isNumberRowEnabled) {
                    if (isSwipeToTypeEnabled && !isNumberMode) {
                        // The swipe decoder maps onto the 3 QWERTY rows; skip the
                        // optional number row band when it is shown.
                        val rowCount = if (isNumberRowEnabled) 4f else 3f
                        val yOffsetRows = rowCount - 3f
                        detectDragGestures(
                            onDragStart = { startOffset ->
                                swipePathPoints.clear()
                                if (keysAreaWidth > 0 && keysAreaHeight > 0) {
                                    val relX = (startOffset.x / keysAreaWidth) * 10f
                                    val relY = (startOffset.y / keysAreaHeight) * rowCount - yOffsetRows
                                    swipePathPoints.add(SwipePoint(relX, relY))
                                }
                            },
                            onDragEnd = {
                                if (swipePathPoints.size > 1) {
                                    val matches = SwipeToTypeEngine.getSwipeWordMatches(
                                        swipePathPoints.toList(),
                                        userVocabulary = topVocabulary.map { it.word }
                                    )
                                    val finalWord = matches.firstOrNull()
                                    if (finalWord != null) {
                                        buzz(HapticFeedbackType.LongPress)
                                        val capitalize = shiftState != ShiftState.OFF ||
                                            (isAutoCapitalizeEnabled && isSentenceStart(currentText()))
                                        val typedText = when {
                                            shiftState == ShiftState.CAPS_LOCK -> finalWord.uppercase()
                                            capitalize -> finalWord.replaceFirstChar { it.uppercase() }
                                            else -> finalWord
                                        }
                                        if (shiftState == ShiftState.SHIFT) shiftState = ShiftState.OFF
                                        onKeyPress(typedText)
                                        onKeyPress(" ")
                                        viewModel.onWordCommitted(finalWord)
                                        viewModel.recordSwipeWordStat()
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
                            onDrag = { change, _ ->
                                change.consume()
                                if (keysAreaWidth > 0 && keysAreaHeight > 0) {
                                    val relX = (change.position.x / keysAreaWidth) * 10f
                                    val relY = (change.position.y / keysAreaHeight) * rowCount - yOffsetRows

                                    val lastPt = swipePathPoints.lastOrNull()
                                    if (lastPt == null || abs(lastPt.x - relX) > 0.05f || abs(lastPt.y - relY) > 0.05f) {
                                        swipePathPoints.add(SwipePoint(relX, relY))
                                    }

                                    // Throttled: rescoring the dictionary on every
                                    // single pointer move is wasted work.
                                    if (swipePathPoints.size > 1 && swipePathPoints.size % 4 == 0) {
                                        val matches = SwipeToTypeEngine.getSwipeWordMatches(
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
                        val isBottomLetterRow = !isNumberMode && rowIndex == bottomLetterRowIndex

                        // Bottom row Shift Key
                        if (isBottomLetterRow) {
                            KeyButton(
                                text = when {
                                    shiftState == ShiftState.CAPS_LOCK -> "⇪"
                                    shiftActive -> "⬆"
                                    else -> "⇧"
                                },
                                onClick = {
                                    buzz(HapticFeedbackType.TextHandleMove)
                                    val now = System.currentTimeMillis()
                                    shiftState = when {
                                        shiftState == ShiftState.SHIFT && now - lastShiftTapTime < 350 -> ShiftState.CAPS_LOCK
                                        shiftState == ShiftState.OFF -> ShiftState.SHIFT
                                        else -> ShiftState.OFF
                                    }
                                    lastShiftTapTime = now
                                },
                                isSpecial = true,
                                isHighlighted = shiftState != ShiftState.OFF,
                                modifier = Modifier
                                    .width(44.dp)
                                    .testTag("key_shift")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        // Standard characters
                        for (char in currentRow) {
                            val isLetter = char.length == 1 && char[0] in 'a'..'z'
                            val keyText = if (shiftActive && isLetter) char.uppercase() else char
                            val variants = if (!isNumberMode) {
                                keyVariants[char].orEmpty().map { if (shiftActive && isLetter) it.uppercase() else it }
                            } else {
                                emptyList()
                            }
                            KeyButton(
                                text = keyText,
                                onClick = {
                                    buzz(HapticFeedbackType.TextHandleMove)
                                    onKeyPress(keyText)
                                    // One-shot shift consumes itself after a letter
                                    if (shiftState == ShiftState.SHIFT && isLetter) {
                                        shiftState = ShiftState.OFF
                                    }
                                },
                                longPressVariants = variants,
                                onVariant = { variant ->
                                    buzz(HapticFeedbackType.LongPress)
                                    onKeyPress(variant)
                                    if (shiftState == ShiftState.SHIFT && isLetter) {
                                        shiftState = ShiftState.OFF
                                    }
                                },
                                modifier = Modifier.testTag("key_$char")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }

                        // Bottom row Backspace Key (undo-aware, repeats on hold)
                        if (isBottomLetterRow) {
                            KeyButton(
                                text = "⌫",
                                onClick = {
                                    buzz(HapticFeedbackType.LongPress)
                                    handleBackspace()
                                },
                                isSpecial = true,
                                repeatOnHold = true,
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
                val rowCount = if (isNumberRowEnabled && !isNumberMode) 4f else 3f
                val yOffsetRows = rowCount - 3f
                Canvas(modifier = Modifier.matchParentSize()) {
                    val path = androidx.compose.ui.graphics.Path()
                    val firstPt = swipePathPoints.first()
                    val startX = (firstPt.x / 10f) * size.width
                    val startY = ((firstPt.y + yOffsetRows) / rowCount) * size.height
                    path.moveTo(startX, startY)

                    for (i in 1 until swipePathPoints.size) {
                        val pt = swipePathPoints[i]
                        val px = (pt.x / 10f) * size.width
                        val py = ((pt.y + yOffsetRows) / rowCount) * size.height
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
                    buzz(HapticFeedbackType.TextHandleMove)
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
                text = if (isOfflineMode) "🔒" else "🚀",
                onClick = {
                    buzz(HapticFeedbackType.LongPress)
                    viewModel.toggleOfflineMode()
                    gestureAlert = if (isOfflineMode) "Cloud AI Mode Enabled" else "Local Privacy Mode Active"
                },
                isSpecial = true,
                modifier = Modifier
                    .width(40.dp)
                    .testTag("key_privacy_toggle")
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Voice input handoff
            KeyButton(
                text = "🎤",
                onClick = {
                    buzz(HapticFeedbackType.LongPress)
                    if (inPlaygroundMode) {
                        gestureAlert = "Voice input works when used as your keyboard 🎤"
                    } else {
                        onMicPress()
                    }
                },
                isSpecial = true,
                modifier = Modifier
                    .width(40.dp)
                    .testTag("key_mic")
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Space Key (smart: expands shortcuts, applies learned auto-corrections,
            // inserts a period on double-tap, and slides to move the cursor)
            KeyButton(
                text = "Space",
                onClick = {
                    buzz(HapticFeedbackType.TextHandleMove)
                    handleSpace()
                },
                onHorizontalDrag = if (inPlaygroundMode) null else onCursorMove,
                modifier = Modifier
                    .weight(1f)
                    .testTag("key_space")
            )

            Spacer(modifier = Modifier.width(4.dp))

            // Period key with punctuation variants on long-press
            KeyButton(
                text = ".",
                onClick = {
                    buzz(HapticFeedbackType.TextHandleMove)
                    onKeyPress(".")
                },
                longPressVariants = keyVariants["."].orEmpty(),
                onVariant = { variant ->
                    buzz(HapticFeedbackType.LongPress)
                    onKeyPress(variant)
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
                    buzz(HapticFeedbackType.LongPress)
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
private fun ClipActionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFE8DEF8))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = Color(0xFF21005D), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun AiActionButton(
    label: String,
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onLongPress: (() -> Unit)? = null
) {
    val containerColor = if (highlighted) Color(0xFFEADDFF) else Color.White
    val borderColor = if (highlighted) Color(0xFFD0BCFF) else Color(0xFFCBD5E1)
    val contentColor = if (highlighted) Color(0xFF21005D) else Color(0xFF1C1B1F)

    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnLongPress by rememberUpdatedState(onLongPress)

    val interactionModifier = if (onLongPress != null) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { currentOnClick() },
                onLongPress = { currentOnLongPress?.invoke() }
            )
        }
    } else {
        Modifier.clickable { currentOnClick() }
    }

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .then(interactionModifier),
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
    isSpecial: Boolean = false,
    isHighlighted: Boolean = false,
    repeatOnHold: Boolean = false,
    longPressVariants: List<String> = emptyList(),
    onVariant: (String) -> Unit = {},
    onHorizontalDrag: ((Int) -> Unit)? = null
) {
    val isEnter = text == "Enter"
    val containerColor = when {
        isEnter -> Color(0xFF6750A4)
        isHighlighted -> Color(0xFFD0BCFF)
        isSpecial -> Color(0xFFE7E0EC)
        else -> Color.White
    }
    val contentColor = if (isEnter) Color.White else Color(0xFF1C1B1F)

    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnVariant by rememberUpdatedState(onVariant)
    val scope = rememberCoroutineScope()
    var showVariants by remember { mutableStateOf(false) }
    val density = LocalDensity.current

    val interactionModifier = when {
        repeatOnHold -> Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    currentOnClick()
                    val repeatJob = scope.launch {
                        delay(450)
                        while (true) {
                            currentOnClick()
                            delay(60)
                        }
                    }
                    tryAwaitRelease()
                    repeatJob.cancel()
                }
            )
        }
        longPressVariants.isNotEmpty() -> Modifier.pointerInput(Unit) {
            detectTapGestures(
                onTap = { currentOnClick() },
                onLongPress = { showVariants = true }
            )
        }
        else -> Modifier.clickable { currentOnClick() }
    }

    // Optional horizontal slide (used by the space bar for cursor control)
    val dragHandler = onHorizontalDrag
    val dragModifier = if (dragHandler != null) {
        Modifier.pointerInput(Unit) {
            var accumulated = 0f
            detectDragGestures(
                onDragStart = { accumulated = 0f },
                onDrag = { change, dragAmount ->
                    change.consume()
                    accumulated += dragAmount.x
                    val steps = (accumulated / 48f).toInt()
                    if (steps != 0) {
                        dragHandler(steps)
                        accumulated -= steps * 48f
                    }
                }
            )
        }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .size(width = 32.dp, height = 44.dp)
            .shadow(1.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .then(dragModifier)
            .then(interactionModifier),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        if (showVariants && longPressVariants.isNotEmpty()) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, with(density) { -(52.dp).roundToPx() }),
                onDismissRequest = { showVariants = false }
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White,
                    shadowElevation = 6.dp
                ) {
                    Row(modifier = Modifier.padding(4.dp)) {
                        longPressVariants.forEach { variant ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        currentOnVariant(variant)
                                        showVariants = false
                                    }
                                    .padding(horizontal = 9.dp, vertical = 8.dp)
                            ) {
                                Text(variant, fontSize = 16.sp, color = Color(0xFF1C1B1F))
                            }
                        }
                    }
                }
            }
        }
    }
}

val RowDefaultsButtonPadding = androidx.compose.foundation.layout.PaddingValues(
    horizontal = 12.dp,
    vertical = 0.dp
)
