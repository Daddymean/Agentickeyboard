package com.example.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.platform.testTag
import com.example.db.ShortcutTemplate
import com.example.ui.AgenticKeyboardLayout
import com.example.ui.KeyboardViewModel
import com.example.ui.RowDefaultsButtonPadding



@Composable
fun PlaygroundTab(viewModel: KeyboardViewModel, onNavigateToShortcuts: () -> Unit) {
    var testText by remember { mutableStateOf("") }
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()

    val grammarCorrection by viewModel.grammarCorrection.collectAsState()
    val toneAnalysis by viewModel.toneAnalysis.collectAsState()
    val summary by viewModel.summary.collectAsState()
    val translation by viewModel.translation.collectAsState()
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
                color = Color(0xFF1C1B1F),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp
            )
            Text(
                "Experience modular cognitive assistance below. Type in the interactive bento sandbox.",
                color = Color(0xFF5F5D6B),
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
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Simulation Text Sandbox",
                            color = Color(0xFF1C1B1F),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF1F5F9))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "Live Input",
                                color = Color(0xFF475569),
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
                        placeholder = { Text("Start typing to observe AI insights or expand abbreviations...", color = Color.Gray) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6750A4),
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            focusedTextColor = Color(0xFF1C1B1F),
                            unfocusedTextColor = Color(0xFF1C1B1F),
                            focusedContainerColor = Color(0xFFF8FAFC),
                            unfocusedContainerColor = Color(0xFFF8FAFC)
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
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8DEF8)),
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
                                .background(Color.White.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🎭", fontSize = 18.sp)
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFD0BCFF))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                "SENTIMENT ANALYSIS",
                                color = Color(0xFF21005D),
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
                            color = Color(0xFF49454F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            sentimentText,
                            color = Color(0xFF21005D),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.5).sp
                        )
                        if (toneAnalysis != null) {
                            Text(
                                "Match confidence: $scoreText",
                                color = Color(0xFF21005D).copy(alpha = 0.7f),
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F2FA)),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
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
                                    .background(Color(0xFFE8DEF8)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🔍", fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "SUMMARIZER",
                                color = Color(0xFF49454F).copy(alpha = 0.7f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            summary ?: "Type longer paragraphs to condense writing into main points.",
                            color = Color(0xFF1C1B1F),
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F)),
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
                                    .background(Color(0xFF334155)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🛡️", fontSize = 14.sp)
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                "PRIVACY ENGINE",
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Column {
                            Text(
                                if (isOfflineMode) "100% Offline" else "Cloud Enabled",
                                color = if (isOfflineMode) Color(0xFF4ADE80) else Color(0xFFFBBF24),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (isOfflineMode) "Local Sandboxing active." else "Enhanced processing.",
                                color = Color.Gray,
                                fontSize = 10.sp,
                                lineHeight = 12.sp
                            )
                        }
                    }
                }
            }
        }

        // Bento Card: Quick Templates/Shortcuts Redirect Card (White with pink accent circle)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(1.dp, RoundedCornerShape(28.dp))
                    .clickable { onNavigateToShortcuts() },
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
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
                            .background(Color(0xFFFFD8E4)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("⚡", fontSize = 20.sp)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Quick Shortcut Templates",
                            color = Color(0xFF31111D),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (shortcuts.isEmpty()) "Register abbreviations like 'omw' to speed up typing."
                            else "Active: ${shortcuts.joinToString(", ") { it.shortcut }}",
                            color = Color(0xFF5F5D6B),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFF1F5F9)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("➔", color = Color(0xFF475569), fontSize = 12.sp)
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
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                    shape = RoundedCornerShape(28.dp),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "AI Rewrite & Grammar Feed",
                            color = Color(0xFF1C1B1F),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        grammarCorrection?.let { grammar ->
                            if (grammar.correctionsCount > 0) {
                                Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text("Spelling: ", color = Color(0xFF475569), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Text("${grammar.correctionsCount} correction(s) -> ${grammar.explanation}", color = Color(0xFF15803D), fontSize = 12.sp)
                                }
                            }
                        }

                        translation?.let { trans ->
                            Row(modifier = Modifier.padding(vertical = 4.dp)) {
                                Text("Translation: ", color = Color(0xFF475569), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(trans, color = Color(0xFF0369A1), fontSize = 12.sp)
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
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE6E1E5)),
                shape = RoundedCornerShape(28.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF7F2FA))
                            .padding(vertical = 8.dp, horizontal = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "⌨️ SIMULATION KEYBOARD ACCESS PLATFORM",
                            color = Color(0xFF49454F),
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
