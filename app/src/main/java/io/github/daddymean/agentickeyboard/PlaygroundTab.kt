package io.github.daddymean.agentickeyboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.ui.AgenticKeyboardLayout
import io.github.daddymean.agentickeyboard.ui.AiPanelState
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel
import io.github.daddymean.agentickeyboard.ui.theme.LocalCompanionColors

@Composable
fun PlaygroundTab(viewModel: KeyboardViewModel, onNavigateToShortcuts: () -> Unit) {
    var testText by remember { mutableStateOf("") }
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val colors = LocalCompanionColors.current

    val aiPanelState by viewModel.aiPanelState.collectAsState()
    val grammarCorrection = (aiPanelState as? AiPanelState.Grammar)?.result
    val toneAnalysis = (aiPanelState as? AiPanelState.Tone)?.result
    val summary = (aiPanelState as? AiPanelState.Summary)?.text
    val translation = (aiPanelState as? AiPanelState.Translation)?.text
    val shortcuts by viewModel.shortcuts.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Bento Workspace",
                color = colors.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp
            )
            Text(
                "Experience modular cognitive assistance below. Type in the interactive bento sandbox.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        // --- BENTO GRID CARD 1: Messaging Sandbox Card ---
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(1.dp, RoundedCornerShape(28.dp)),
                colors = CardDefaults.cardColors(containerColor = colors.card),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, colors.border)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Simulation Text Sandbox",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.surfaceMuted)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "Live Input",
                                color = colors.textTertiary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = testText,
                        onValueChange = {
                            testText = it
                            viewModel.setInputText(it)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("playground_input"),
                        placeholder = {
                            Text(
                                "Start typing to observe AI insights or expand abbreviations...",
                                color = colors.textSecondary
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.accent,
                            unfocusedBorderColor = colors.borderStrong,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary,
                            focusedContainerColor = colors.cardSubtle,
                            unfocusedContainerColor = colors.cardSubtle
                        ),
                        shape = RoundedCornerShape(16.dp),
                        minLines = 3,
                        maxLines = 5
                    )
                }
            }
        }

        // --- BENTO GRID CARDS: COGNITIVE INSIGHTS COHORT ---
        // We arrange metrics as beautiful, structured Bento grid elements!

        // Bento Card: Tone / Sentiment Analysis
        item {
            val sentimentText = toneAnalysis?.sentiment ?: "Awaiting text..."
            val scoreText = toneAnalysis?.let { "${(it.toneScore * 100).toInt()}% Match" } ?: "No analysis"

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(1.dp, RoundedCornerShape(28.dp)),
                colors = CardDefaults.cardColors(containerColor = colors.cardAccent),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.card.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🎭", fontSize = 18.sp)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.cardAccentBadge)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "SENTIMENT ANALYSIS",
                                color = colors.onAccentContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Column {
                        Text(
                            "Current Tone Analysis",
                            color = colors.onAccentContainer.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            sentimentText,
                            color = colors.onAccentContainer,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        )
                        if (toneAnalysis != null) {
                            Text(
                                "Match confidence: $scoreText",
                                color = colors.onAccentContainer.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }

        // Row of Bento Cards: [Summarizer] and [Privacy Mode] Side-by-Side
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Summarizer Bento Block
                Card(
                    modifier = Modifier
                        .weight(1.2f)
                        .height(170.dp)
                        .shadow(1.dp, RoundedCornerShape(28.dp)),
                    colors = CardDefaults.cardColors(containerColor = colors.cardTinted),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(colors.cardAccent),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🔍", fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "SUMMARIZER",
                                color = colors.textSecondary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            summary ?: "Type longer paragraphs to condense writing into main points.",
                            color = colors.textPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 16.sp,
                            maxLines = 4
                        )
                    }
                }

                // Privacy / State Bento Block (Dark, elegant slate)
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(170.dp)
                        .shadow(1.dp, RoundedCornerShape(28.dp))
                        .clickable { viewModel.toggleOfflineMode() },
                    colors = CardDefaults.cardColors(containerColor = colors.cardDark),
                    shape = RoundedCornerShape(28.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(colors.cardDarkChip),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🛡️", fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "PRIVACY ENGINE",
                                color = colors.onCardDark.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column {
                            Text(
                                if (isOfflineMode) "100% Offline" else "Cloud Enabled",
                                color = if (isOfflineMode) colors.onCardDarkAccent else colors.warning,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (isOfflineMode) "Local Sandboxing active." else "Enhanced processing.",
                                color = colors.onCardDark.copy(alpha = 0.65f),
                                fontSize = 10.sp,
                                lineHeight = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Bento Card: Quick Templates/Shortcuts Redirect Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(1.dp, RoundedCornerShape(28.dp))
                    .clickable { onNavigateToShortcuts() },
                colors = CardDefaults.cardColors(containerColor = colors.card),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, colors.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(colors.pinkContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚡", fontSize = 20.sp)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Quick Shortcut Templates",
                            color = colors.onPinkContainer,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (shortcuts.isEmpty()) "Register abbreviations like 'omw' to speed up typing."
                            else "Active: ${shortcuts.joinToString(", ") { it.shortcut }}",
                            color = colors.textSecondary,
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceMuted),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("➔", color = colors.textTertiary, fontSize = 12.sp)
                    }
                }
            }
        }

        // Optional Grammar & Translation bento block when available
        if (grammarCorrection != null || translation != null) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(1.dp, RoundedCornerShape(28.dp)),
                    colors = CardDefaults.cardColors(containerColor = colors.surfaceMuted),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "AI Rewrite & Grammar Feed",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        grammarCorrection?.let { grammar ->
                            if (grammar.correctionsCount > 0) {
                                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text("Spelling: ", color = colors.textTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("${grammar.correctionsCount} correction(s) -> ${grammar.explanation}", color = colors.success, fontSize = 12.sp)
                                }
                            }
                        }

                        translation?.let { trans ->
                            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("Translation: ", color = colors.textTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(trans, color = colors.info, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // --- BENTO GRID CARD: Keyboard Panel Card ---
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(2.dp, RoundedCornerShape(28.dp)),
                colors = CardDefaults.cardColors(containerColor = colors.keyboardFrame),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, colors.border)
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.cardTinted)
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "⌨️ SIMULATION KEYBOARD ACCESS PLATFORM",
                            color = colors.textSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }

                    AgenticKeyboardLayout(
                        viewModel = viewModel,
                        onKeyPress = { text ->
                            testText += text
                            viewModel.setInputText(testText)
                        },
                        onDelete = {
                            if (testText.isNotEmpty()) {
                                testText = testText.dropLast(1)
                                viewModel.setInputText(testText)
                            }
                        },
                        onAction = {
                            testText += "\n"
                            viewModel.setInputText(testText)
                        },
                        inPlaygroundMode = true,
                        playgroundTextState = testText,
                        onPlaygroundTextChange = {
                            testText = it
                            viewModel.setInputText(it)
                        }
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
