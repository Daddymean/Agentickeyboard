package io.github.daddymean.agentickeyboard.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.AgenticKeyboardApplication
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import io.github.daddymean.agentickeyboard.db.AppPersona
import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.ui.UsageStats
import io.github.daddymean.agentickeyboard.db.UserVocabulary
import io.github.daddymean.agentickeyboard.util.AppPersonas
import io.github.daddymean.agentickeyboard.util.PersonalModelSerializer
import io.github.daddymean.agentickeyboard.SettingSwitchRow

@Composable
fun ExportTabHeader() {
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "Style Hub",
        color = Color(0xFF1C1B1F),
        fontSize = 24.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.8).sp
    )
    Text(
        "Manage your on-device personalization engine and private communication style parameters.",
        color = Color(0xFF5F5D6B),
        fontSize = 12.sp,
        lineHeight = 16.sp
    )
}

@Composable
fun PersonaSelectionCard(
    userPersonaPreference: String,
    onPersonaSelected: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🧠 AI Style Persona",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Choose the writing style personality that the AI adapts to when crafting responses and completions.",
                color = Color(0xFF5F5D6B),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            val personas = listOf("Match my history", "Professional", "Joyful", "Empathetic", "Casual")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                personas.take(3).forEach { persona ->
                    val isSelected = userPersonaPreference == persona
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFFE8DEF8) else Color(0xFFF1F5F9))
                            .clickable { onPersonaSelected(persona) }
                            .border(1.dp, if (isSelected) Color(0xFF6750A4) else Color.Transparent, RoundedCornerShape(12.dp))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = persona,
                            color = if (isSelected) Color(0xFF21005D) else Color(0xFF49454F),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                personas.drop(3).forEach { persona ->
                    val isSelected = userPersonaPreference == persona
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFFE8DEF8) else Color(0xFFF1F5F9))
                            .clickable { onPersonaSelected(persona) }
                            .border(1.dp, if (isSelected) Color(0xFF6750A4) else Color.Transparent, RoundedCornerShape(12.dp))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = persona,
                            color = if (isSelected) Color(0xFF21005D) else Color(0xFF49454F),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PerAppPersonasCard(
    appPersonas: List<AppPersona>,
    availablePersonas: List<String>,
    onAppPersonaSelected: (String, String, String) -> Unit,
    onRemoveAppPersona: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "📱 Per-app personas",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "The keyboard remembers the persona you pick in each app and reapplies it automatically. Review or change the mappings here.",
                color = Color(0xFF5F5D6B),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (appPersonas.isEmpty()) {
                Text(
                    "No apps yet — pick a persona while typing in an app and it'll appear here.",
                    color = Color(0xFF9A97A6),
                    fontSize = 11.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            } else {
                appPersonas.forEach { mapping ->
                    key(mapping.packageName) {
                        var expanded by remember { mutableStateOf(false) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                AppPersonas.friendlyName(mapping.appLabel, mapping.packageName),
                                color = Color(0xFF1C1B1F),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Box {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFE8DEF8))
                                        .clickable { expanded = true }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        mapping.persona,
                                        color = Color(0xFF21005D),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    availablePersonas.forEach { persona ->
                                        DropdownMenuItem(
                                            text = { Text(persona, fontSize = 13.sp) },
                                            onClick = {
                                                onAppPersonaSelected(
                                                    mapping.packageName,
                                                    mapping.appLabel,
                                                    persona
                                                )
                                                expanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { onRemoveAppPersona(mapping.packageName) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove ${AppPersonas.friendlyName(mapping.appLabel, mapping.packageName)}",
                                    tint = Color(0xFF9A97A6)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KeyboardSettingsCard(
    isAutoCapitalize: Boolean,
    onAutoCapitalizeChange: (Boolean) -> Unit,
    isNumberRow: Boolean,
    onNumberRowChange: (Boolean) -> Unit,
    isProofread: Boolean,
    onProofreadChange: (Boolean) -> Unit,
    isLearningPaused: Boolean,
    onLearningPausedChange: (Boolean) -> Unit,
    isHaptics: Boolean,
    onHapticsChange: (Boolean) -> Unit,
    isVoiceLock: Boolean,
    onVoiceLockChange: (Boolean) -> Unit,
    isSendGuard: Boolean,
    onSendGuardChange: (Boolean) -> Unit,
    themeOverride: String,
    onThemeOverrideChange: (String) -> Unit,
    availableThemes: List<String>,
    onDeviceAiStatus: OnDeviceAiStatus,
    retentionDays: Int,
    onRetentionDaysChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "⌨️ Keyboard Settings",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            SettingSwitchRow(
                title = "Auto-capitalize sentences",
                description = "Shift arms itself after . ! ? and at the start of a field.",
                checked = isAutoCapitalize,
                onCheckedChange = onAutoCapitalizeChange
            )
            SettingSwitchRow(
                title = "Number row",
                description = "Show a dedicated 1-0 row above the letters.",
                checked = isNumberRow,
                onCheckedChange = onNumberRowChange
            )
            SettingSwitchRow(
                title = "Proofread as you type",
                description = "Quietly checks grammar in the background and offers one-tap fixes. Sends drafts to the cloud, so it is off by default.",
                checked = isProofread,
                onCheckedChange = onProofreadChange
            )
            SettingSwitchRow(
                title = "Pause learning",
                description = "Incognito for the personalization engine: stop learning vocabulary, word pairs, and corrections.",
                checked = isLearningPaused,
                onCheckedChange = onLearningPausedChange
            )
            SettingSwitchRow(
                title = "Haptic feedback",
                description = "Vibrate on key presses and gestures.",
                checked = isHaptics,
                onCheckedChange = onHapticsChange
            )
            SettingSwitchRow(
                title = "Voice-lock",
                description = "AI rewrite, compose, and continue keep your own phrasing: minimal edits, no overproduced tone.",
                checked = isVoiceLock,
                onCheckedChange = onVoiceLockChange
            )
            SettingSwitchRow(
                title = "Send-guard",
                description = "Pauses Send once when a draft reads hostile so you can confirm or soften it. Checked locally on-device.",
                checked = isSendGuard,
                onCheckedChange = onSendGuardChange
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Keyboard theme",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                "Pin the keyboard to Light or Dark, or follow the system setting.",
                color = Color(0xFF5F5D6B),
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                availableThemes.forEach { mode ->
                    val isSelected = themeOverride == mode
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFFE8DEF8) else Color(0xFFF1F5F9))
                            .clickable { onThemeOverrideChange(mode) }
                            .border(1.dp, if (isSelected) Color(0xFF6750A4) else Color.Transparent, RoundedCornerShape(12.dp))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            mode,
                            color = if (isSelected) Color(0xFF21005D) else Color(0xFF49454F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "On-device AI",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                when (onDeviceAiStatus) {
                    OnDeviceAiStatus.AVAILABLE -> "Available — offline Fix Grammar, Rewrite, Summarize, replies, compose, continue, and tone run on this device (Gemini Nano)."
                    OnDeviceAiStatus.DOWNLOADING -> "Downloading the on-device model…"
                    OnDeviceAiStatus.CHECKING -> "Checking device support…"
                    OnDeviceAiStatus.UNSUPPORTED -> "Not supported on this device — offline mode uses basic local helpers."
                },
                color = Color(0xFF5F5D6B),
                fontSize = 10.sp
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Writing-log retention",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                "Logs older than this are deleted automatically.",
                color = Color(0xFF5F5D6B),
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(7, 30, 90).forEach { days ->
                    val isSelected = retentionDays == days
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFFE8DEF8) else Color(0xFFF1F5F9))
                            .clickable { onRetentionDaysChange(days) }
                            .border(1.dp, if (isSelected) Color(0xFF6750A4) else Color.Transparent, RoundedCornerShape(12.dp))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "$days days",
                            color = if (isSelected) Color(0xFF21005D) else Color(0xFF49454F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun LocalUsageDashboardCard(usageStats: UsageStats) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1B1F)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "📊 USAGE DASHBOARD (ON-DEVICE)",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                DashboardStat("Auto-fixes", usageStats.autoCorrections)
                DashboardStat("Swipes", usageStats.swipeWords)
                DashboardStat("AI applies", usageStats.aiApplies)
                DashboardStat("Shortcuts", usageStats.shortcutExpansions)
            }
        }
    }
}

@Composable
fun DashboardStat(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            color = Color.White,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 10.sp
        )
    }
}

@Composable
fun OnDeviceStatisticsCard(topVocabularySize: Int, learnedCorrectionsSize: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .shadow(1.dp, RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8DEF8)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Vocabulary Index", color = Color(0xFF21005D).copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    "$topVocabularySize words",
                    color = Color(0xFF21005D),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Card(
            modifier = Modifier
                .weight(1f)
                .shadow(1.dp, RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F2FA)),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Auto-Corrections", color = Color(0xFF49454F), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    "$learnedCorrectionsSize rules",
                    color = Color(0xFF1C1B1F),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun PersonalizedVocabularyCard(
    topVocabulary: List<UserVocabulary>,
    onClearVocabulary: () -> Unit,
    context: Context
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📈 Learned Vocabulary",
                    color = Color(0xFF1C1B1F),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Button(
                    onClick = {
                        onClearVocabulary()
                        Toast.makeText(context, "Cleared learned vocabulary!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFFC62828)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Clear", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Vocabulary keywords captured completely offline from active typing inputs to train real-time autocompletes.",
                color = Color(0xFF5F5D6B),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (topVocabulary.isEmpty()) {
                Text("No vocabulary parsed yet. Start typing on the keyboard!", color = Color.Gray, fontSize = 11.sp)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    topVocabulary.take(4).forEach { vocab ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF1F5F9))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${vocab.word} (${vocab.count})",
                                color = Color(0xFF334155),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                if (topVocabulary.size > 4) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        topVocabulary.drop(4).take(4).forEach { vocab ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFFF1F5F9))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${vocab.word} (${vocab.count})",
                                    color = Color(0xFF334155),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SpellingCorrectionsCard(
    learnedCorrections: List<LearnedCorrection>,
    onClearCorrections: () -> Unit,
    onDeleteCorrection: (Int) -> Unit,
    context: Context
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🔧 Spelling Corrections",
                    color = Color(0xFF1C1B1F),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Button(
                    onClick = {
                        onClearCorrections()
                        Toast.makeText(context, "Cleared spelling corrections!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFFC62828)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Clear All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Spelling typos and corrections learned organically on-device based on your typing history.",
                color = Color(0xFF5F5D6B),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (learnedCorrections.isEmpty()) {
                Text("No spelling corrections registered yet.", color = Color.Gray, fontSize = 11.sp)
            } else {
                learnedCorrections.take(4).forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFF8FAFC))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.typo,
                                color = Color(0xFFEF4444),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = " ➔ ",
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                            Text(
                                text = item.correction,
                                color = Color(0xFF22C55E),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = " (${item.count} times)",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        IconButton(
                            onClick = { onDeleteCorrection(item.id) },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Correction",
                                tint = Color.Gray,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SecureExportSettingsCard(
    stripSensitive: Boolean,
    onStripSensitiveChange: (Boolean) -> Unit,
    exportFormat: String,
    onExportFormatChange: (String) -> Unit,
    totalRedactions: Int,
    emailsRedacted: Int,
    phonesRedacted: Int,
    cardsRedacted: Int,
    urlsRedacted: Int,
    ipAddressesRedacted: Int,
    numericIdsRedacted: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🔐 Secure Export Settings",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Configure privacy redactions and formatting before serializing patterns for external personal model training.",
                color = Color(0xFF5F5D6B),
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // 1. Strip Sensitive Switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Strip Sensitive Identifiers",
                        color = Color(0xFF1C1B1F),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                    Text(
                        "Redacts emails, phone numbers, financials, IPs, URLs, and private IDs completely offline.",
                        color = Color(0xFF5F5D6B),
                        fontSize = 10.sp,
                        lineHeight = 12.sp
                    )
                }
                Switch(
                    checked = stripSensitive,
                    onCheckedChange = onStripSensitiveChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = Color(0xFF6750A4)
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 2. Export Format Selection
            Text(
                "Export Serialization Format",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val formats = listOf("JSON Structure", "Base64 Cipher Block")
                formats.forEach { format ->
                    val isSelected = exportFormat == format
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) Color(0xFFE8DEF8) else Color(0xFFF1F5F9))
                            .clickable { onExportFormatChange(format) }
                            .border(1.dp, if (isSelected) Color(0xFF6750A4) else Color.Transparent, RoundedCornerShape(12.dp))
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = format,
                            color = if (isSelected) Color(0xFF21005D) else Color(0xFF49454F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 3. Redactions Stats Banner (if enabled and redactions found)
            if (stripSensitive && totalRedactions > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFE8F5E9))
                        .border(1.dp, Color(0xFF2E7D32), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                ) {
                    Column {
                        Text(
                            "🛡️ Privacy Shield Active",
                            color = Color(0xFF1B5E20),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            "Redacted $totalRedactions sensitive items: " +
                            listOfNotNull(
                                if (emailsRedacted > 0) "$emailsRedacted email(s)" else null,
                                if (phonesRedacted > 0) "$phonesRedacted phone(s)" else null,
                                if (cardsRedacted > 0) "$cardsRedacted financial token(s)" else null,
                                if (urlsRedacted > 0) "$urlsRedacted link(s)" else null,
                                if (ipAddressesRedacted > 0) "$ipAddressesRedacted IP(s)" else null,
                                if (numericIdsRedacted > 0) "$numericIdsRedacted numeric ID(s)" else null
                            ).joinToString(", "),
                            color = Color(0xFF2E7D32),
                            fontSize = 10.sp,
                            lineHeight = 12.sp
                        )
                    }
                }
            } else if (stripSensitive) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF1F5F9))
                        .padding(10.dp)
                ) {
                    Text(
                        "✅ No sensitive identifiers detected in typing history.",
                        color = Color(0xFF475569),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun ExportDataCard(
    compiledJson: String,
    clipboardManager: ClipboardManager,
    context: Context,
    onClearLogs: () -> Unit,
    onImportLogs: (String, (Int) -> Unit) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(28.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Telemetry Style Data (JSON)",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Export style parameters to train offline custom AI models to replicate your unique writing and vocabulary patterns.",
                color = Color(0xFF5F5D6B),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1C1B1F))
                    .padding(12.dp)
            ) {
                LazyColumn {
                    item {
                        Text(
                            text = compiledJson,
                            color = Color(0xFF4ADE80),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(compiledJson))
                        Toast.makeText(context, "Copied telemetry JSON to clipboard!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("copy_export_button")
                ) {
                    Text("Copy JSON", color = Color.White, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        onClearLogs()
                        Toast.makeText(context, "Cleared local database writing history!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("clear_export_button")
                ) {
                    Text("Clear Logs", color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Restore a previous export (device migration / backup)
            Button(
                onClick = {
                    val clip = clipboardManager.getText()?.text
                    if (clip.isNullOrBlank()) {
                        Toast.makeText(context, "Copy an exported payload to the clipboard first.", Toast.LENGTH_SHORT).show()
                    } else {
                        onImportLogs(clip) { imported ->
                            val message = if (imported < 0) {
                                "Clipboard does not contain a valid export."
                            } else {
                                "Imported $imported personalization record(s)!"
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("import_button")
            ) {
                Text("Import from Clipboard", color = Color(0xFF6750A4), fontWeight = FontWeight.Bold)
            }
        }
    }
}
