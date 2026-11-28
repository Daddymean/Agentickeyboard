package io.github.daddymean.agentickeyboard

import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.ondevice.GemmaSettingsSection
import io.github.daddymean.agentickeyboard.ui.GeminiApiKeyCard
import io.github.daddymean.agentickeyboard.ui.KeyboardMasteryCard
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel
import io.github.daddymean.agentickeyboard.ui.RowDefaultsButtonPadding
import io.github.daddymean.agentickeyboard.ui.theme.LocalCompanionColors
import io.github.daddymean.agentickeyboard.util.AppPersonas
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.LatencyMeter
import io.github.daddymean.agentickeyboard.util.LearnedRuleCleanup
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import io.github.daddymean.agentickeyboard.util.PersonalModelSerializer

@Composable
fun ExportTab(viewModel: KeyboardViewModel) {
    val colors = LocalCompanionColors.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Opened directly by the keyboard's settings gear, so these come first.
        item { GeminiApiKeyCard() }
        item { KeyboardSettingsCard(viewModel) }

        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Style Hub",
                color = colors.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp
            )
            Text(
                "Manage your on-device personalization engine and private communication style parameters.",
                color = colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        item { KeyboardMasteryCard(viewModel) }
        item { StylePersonaCard(viewModel) }
        item { AppPersonasCard(viewModel) }
        item { UsageDashboardCard(viewModel) }
        item { PersonalizationStatsRow(viewModel) }
        item { LearnedVocabularyCard(viewModel) }
        item { SpellingCorrectionsCard(viewModel) }
        item { PersonalModelExportSection(viewModel) }
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun KeyboardSettingsCard(viewModel: KeyboardViewModel) {
    val colors = LocalCompanionColors.current
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val isAutoCapitalize by viewModel.isAutoCapitalizeEnabled.collectAsState()
    val isAutoFix by viewModel.isAutoFixEnabled.collectAsState()
    val isNumberRow by viewModel.isNumberRowEnabled.collectAsState()
    val glossaryText by viewModel.projectGlossaryText.collectAsState()
    val keyHeightScale by viewModel.keyHeightScale.collectAsState()
    val isProofread by viewModel.isProofreadEnabled.collectAsState()
    val isLearningPaused by viewModel.isLearningPaused.collectAsState()
    val isHaptics by viewModel.isHapticsEnabled.collectAsState()
    val isVoiceLock by viewModel.isVoiceLockEnabled.collectAsState()
    val isSendGuard by viewModel.isSendGuardEnabled.collectAsState()
    val isAiApplyConfirm by viewModel.isAiApplyConfirmEnabled.collectAsState()
    val isSyncNotes by viewModel.isSyncNotesToCloud.collectAsState()
    val themeOverride by viewModel.themeOverride.collectAsState()
    val onDeviceAiStatus by (context.applicationContext as AgenticKeyboardApplication)
        .onDeviceAi.status.collectAsState()

    var retentionDays by remember { mutableStateOf(viewModel.getLogRetentionDays()) }
    var haggleOn by remember { mutableStateOf(viewModel.isHaggleModeEnabled()) }
    var haggleStatus by remember { mutableStateOf("") }
    var toneOut by remember { mutableStateOf(viewModel.isToneMirrorOutgoing()) }
    var toneIn by remember { mutableStateOf(viewModel.isToneMirrorIncoming()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "⌨️ Keyboard Settings",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            SettingSwitchRow(
                title = "Auto-capitalize sentences",
                description = "Shift arms itself after . ! ? and at the start of a field.",
                checked = isAutoCapitalize,
                onCheckedChange = { viewModel.setAutoCapitalizeEnabled(it) }
            )
            SettingSwitchRow(
                title = "Auto-fix typos on space",
                description = "Fixes a clear typo when you press space; ⌫ right after puts it back. Never touches names, slang or your own words.",
                checked = isAutoFix,
                onCheckedChange = { viewModel.setAutoFixEnabled(it) }
            )
            SettingSwitchRow(
                title = "Haggle mode",
                description = "Suggests counter-offers from your imported Flip sold prices when your draft names an item and a price. Suggestions only; it never types or sends for you. Off in password/incognito fields.",
                checked = haggleOn,
                onCheckedChange = { haggleOn = it; viewModel.setHaggleModeEnabled(it) }
            )
            if (haggleOn) {
                TextButton(onClick = {
                    val json = clipboardManager.getText()?.text.orEmpty()
                    val n = viewModel.importHaggleCache(json)
                    haggleStatus = if (n > 0) "Imported $n Flip price comps." else "Clipboard has no valid Flip cache (version 1 JSON)."
                }) { Text("Import Flip price cache from clipboard", fontSize = 11.sp) }
                if (haggleStatus.isNotEmpty()) {
                    Text(haggleStatus, fontSize = 10.sp, color = colors.textSecondary)
                }
            }
            SettingSwitchRow(
                title = "Tone mirror: my drafts",
                description = "Shows how your draft may land (curt, warm, pushy) before you send. Checked on this phone; never in password or incognito fields.",
                checked = toneOut,
                onCheckedChange = { toneOut = it; viewModel.setToneMirrorOutgoing(it) }
            )
            SettingSwitchRow(
                title = "Tone mirror: pressure flags",
                description = "Flags false choices, assumed yeses and fake urgency in their last message. Uses the same allowed-app read as the mood badge. Off by default.",
                checked = toneIn,
                onCheckedChange = { toneIn = it; viewModel.setToneMirrorIncoming(it) }
            )
            OutlinedTextField(
                value = glossaryText,
                onValueChange = { viewModel.setProjectGlossary(it) },
                label = { Text("Project glossary (one term per line)", fontSize = 11.sp) },
                supportingText = {
                    Text(
                        "Only words you list here are protected from auto-fix. Nothing is learned from typing.",
                        fontSize = 10.sp
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 5
            )
            SettingSwitchRow(
                title = "Number row",
                description = "Show a dedicated 1-0 row above the letters.",
                checked = isNumberRow,
                onCheckedChange = { viewModel.setNumberRowEnabled(it) }
            )
            // Key size: width already fills the screen, so this sets key height
            // (and letter size with it).
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Key size", color = colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    "Taller keys and bigger letters for easier typing.",
                    color = colors.textSecondary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KeyboardSettings.KEY_SIZE_PRESETS.forEach { (label, scale) ->
                        val selected = kotlin.math.abs(keyHeightScale - scale) < 0.01f
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) colors.accent else colors.surfaceMuted)
                                .clickable { viewModel.setKeyHeightScale(scale) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                .testTag("key_size_$label")
                        ) {
                            Text(
                                label,
                                color = if (selected) colors.onAccent else colors.textPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            SettingSwitchRow(
                title = "Proofread as you type",
                description = "Quietly checks grammar in the background and offers one-tap fixes. Sends drafts to the cloud, so it is off by default.",
                checked = isProofread,
                onCheckedChange = { viewModel.setProofreadEnabled(it) }
            )
            SettingSwitchRow(
                title = "Pause learning",
                description = "Incognito for the personalization engine: stop learning vocabulary, word pairs, and corrections, and stop grammar fix and tone check from saving to the writing log.",
                checked = isLearningPaused,
                onCheckedChange = { viewModel.setLearningPaused(it) }
            )
            SettingSwitchRow(
                title = "Haptic feedback",
                description = "Vibrate on key presses and gestures.",
                checked = isHaptics,
                onCheckedChange = { viewModel.setHapticsEnabled(it) }
            )
            SettingSwitchRow(
                title = "Voice-lock",
                description = "AI rewrite, compose, and continue keep your own phrasing: minimal edits, no overproduced tone.",
                checked = isVoiceLock,
                onCheckedChange = { viewModel.setVoiceLockEnabled(it) }
            )
            SettingSwitchRow(
                title = "Send-guard",
                description = "Pauses Send once when a draft reads hostile so you can confirm or soften it. Checked locally on-device.",
                checked = isSendGuard,
                onCheckedChange = { viewModel.setSendGuardEnabled(it) }
            )
            SettingSwitchRow(
                title = "Confirm AI apply",
                description = "Before an AI result is written into a field, shows which app and a preview of the text so you confirm or cancel.",
                checked = isAiApplyConfirm,
                onCheckedChange = { viewModel.setAiApplyConfirmEnabled(it) }
            )
            SettingSwitchRow(
                title = "Sync notes to cloud",
                description = "Notes saved from the keyboard's \"Save note\" action stay on this phone unless enabled; only then may they sync to your Supabase.",
                checked = isSyncNotes,
                onCheckedChange = { viewModel.setSyncNotesToCloud(it) }
            )

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Keyboard theme",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                "Pin the app and keyboard to Light or Dark, or follow the system setting.",
                color = colors.textSecondary,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                KeyboardViewModel.THEME_MODES.forEach { mode ->
                    ChoiceOptionChip(
                        label = mode,
                        selected = themeOverride == mode,
                        onClick = { viewModel.setThemeOverride(mode) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "On-device AI",
                color = colors.textPrimary,
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
                color = colors.textSecondary,
                fontSize = 10.sp
            )
            GemmaSettingsSection()

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Writing-log retention",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                "Logs older than this are deleted automatically.",
                color = colors.textSecondary,
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(7, 30, 90).forEach { days ->
                    ChoiceOptionChip(
                        label = "$days days",
                        selected = retentionDays == days,
                        onClick = {
                            retentionDays = days
                            viewModel.setLogRetentionDays(days)
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun StylePersonaCard(viewModel: KeyboardViewModel) {
    val colors = LocalCompanionColors.current
    val userPersonaPreference by viewModel.userPersonaPreference.collectAsState()
    val personas = listOf("Match my history", "Professional", "Joyful", "Empathetic", "Casual")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🧠 AI Style Persona",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Choose the writing style personality that the AI adapts to when crafting responses and completions.",
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                personas.take(3).forEach { persona ->
                    ChoiceOptionChip(
                        label = persona,
                        selected = userPersonaPreference == persona,
                        onClick = { viewModel.setUserPersonaPreference(persona) },
                        modifier = Modifier.weight(1f),
                        fontSize = 10.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                personas.drop(3).forEach { persona ->
                    ChoiceOptionChip(
                        label = persona,
                        selected = userPersonaPreference == persona,
                        onClick = { viewModel.setUserPersonaPreference(persona) },
                        modifier = Modifier.weight(1f),
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AppPersonasCard(viewModel: KeyboardViewModel) {
    val colors = LocalCompanionColors.current
    val appPersonas by viewModel.appPersonas.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "📱 Per-app personas",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "The keyboard remembers the persona you pick in each app and reapplies it automatically. Review or change the mappings here.",
                color = colors.textSecondary,
                fontSize = 11.sp,
                lineHeight = 14.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (appPersonas.isEmpty()) {
                Text(
                    "No apps yet — pick a persona while typing in an app and it'll appear here.",
                    color = colors.textTertiary,
                    fontSize = 11.sp,
                    fontStyle = FontStyle.Italic
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
                                color = colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Box {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(colors.cardAccent)
                                        .clickable { expanded = true }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        mapping.persona,
                                        color = colors.onAccentContainer,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false }
                                ) {
                                    KeyboardViewModel.PERSONAS.forEach { persona ->
                                        DropdownMenuItem(
                                            text = { Text(persona, fontSize = 13.sp) },
                                            onClick = {
                                                viewModel.setAppPersonaOverride(
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
                            IconButton(onClick = { viewModel.removeAppPersona(mapping.packageName) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove ${AppPersonas.friendlyName(mapping.appLabel, mapping.packageName)}",
                                    tint = colors.textTertiary
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
private fun UsageDashboardCard(viewModel: KeyboardViewModel) {
    val colors = LocalCompanionColors.current
    val usageStats by viewModel.usageStats.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.cardDark),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "📊 USAGE DASHBOARD (ON-DEVICE)",
                color = colors.onCardDark.copy(alpha = 0.6f),
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
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "⏱ TYPING SPEED, RECENT (TYPICAL / SLOW, MS)",
                color = colors.onCardDark.copy(alpha = 0.6f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            // Timings only, held in memory; read again whenever the counters above change.
            val latency = remember(usageStats) {
                LatencyMeter.Metric.entries.map { it to LatencyMeter.summary(it) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                latency.forEach { (metric, summary) -> LatencyStat(metric.label, summary) }
            }
        }
    }
}

@Composable
private fun PersonalizationStatsRow(viewModel: KeyboardViewModel) {
    val colors = LocalCompanionColors.current
    val topVocabulary by viewModel.topVocabulary.collectAsState()
    val learnedCorrections by viewModel.learnedCorrections.collectAsState()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .shadow(1.dp, RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = colors.cardAccent),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Vocabulary Index",
                    color = colors.onAccentContainer.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${topVocabulary.size} words",
                    color = colors.onAccentContainer,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Card(
            modifier = Modifier
                .weight(1f)
                .shadow(1.dp, RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = colors.cardTinted),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, colors.border)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Auto-Corrections", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${learnedCorrections.size} rules",
                    color = colors.textPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun LearnedVocabularyCard(viewModel: KeyboardViewModel) {
    val colors = LocalCompanionColors.current
    val context = LocalContext.current
    val topVocabulary by viewModel.topVocabulary.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "📈 Learned Vocabulary",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Button(
                    onClick = {
                        viewModel.clearVocabulary()
                        Toast.makeText(context, "Cleared learned vocabulary!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = colors.danger),
                    contentPadding = RowDefaultsButtonPadding,
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Clear", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Vocabulary keywords captured completely offline from active typing inputs to train real-time autocompletes.",
                color = colors.textSecondary,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (topVocabulary.isEmpty()) {
                Text("No vocabulary parsed yet. Start typing on the keyboard!", color = colors.textTertiary, fontSize = 11.sp)
            } else {
                topVocabulary.take(8).chunked(4).forEachIndexed { rowIndex, rowItems ->
                    if (rowIndex > 0) Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        rowItems.forEach { vocab ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(colors.surfaceMuted)
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${vocab.word} (${vocab.count})",
                                    color = colors.textPrimary,
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
private fun SpellingCorrectionsCard(viewModel: KeyboardViewModel) {
    val colors = LocalCompanionColors.current
    val context = LocalContext.current
    val learnedCorrections by viewModel.learnedCorrections.collectAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(24.dp)),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🔧 Spelling Corrections",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Button(
                    onClick = {
                        viewModel.clearCorrections()
                        Toast.makeText(context, "Cleared spelling corrections!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = colors.danger),
                    contentPadding = RowDefaultsButtonPadding,
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Clear All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Spelling typos and corrections learned organically on-device based on your typing history.",
                color = colors.textSecondary,
                fontSize = 11.sp
            )
            // KEYBOARD-008: rules removed by the one-time cleanup can be put back.
            val cleanupPrefs = remember {
                context.getSharedPreferences(LearnedRuleCleanup.PREFS_NAME, Context.MODE_PRIVATE)
            }
            var removedRuleCount by remember { mutableStateOf(LearnedRuleCleanup.backupCount(cleanupPrefs)) }
            if (removedRuleCount > 0) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "An update removed $removedRuleCount rules that changed real words (for example your → youre).",
                    color = colors.textSecondary,
                    fontSize = 11.sp
                )
                Button(
                    onClick = {
                        viewModel.restoreCleanedUpCorrections(cleanupPrefs) { restored ->
                            removedRuleCount = 0
                            Toast.makeText(context, "Restored $restored rules", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = colors.info),
                    contentPadding = RowDefaultsButtonPadding,
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Restore removed rules ($removedRuleCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            if (learnedCorrections.isEmpty()) {
                Text("No spelling corrections registered yet.", color = colors.textTertiary, fontSize = 11.sp)
            } else {
                learnedCorrections.take(4).forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.cardSubtle)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = item.typo,
                                color = colors.danger,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = " ➔ ",
                                color = colors.textTertiary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = item.correction,
                                color = colors.success,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                text = " (${item.count} times)",
                                color = colors.textTertiary,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                        IconButton(
                            onClick = { viewModel.deleteCorrection(item.id) },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Correction",
                                tint = colors.textTertiary,
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
private fun PersonalModelExportSection(viewModel: KeyboardViewModel) {
    val colors = LocalCompanionColors.current
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    val logs by viewModel.logs.collectAsState()
    val topVocabulary by viewModel.topVocabulary.collectAsState()
    val learnedCorrections by viewModel.learnedCorrections.collectAsState()
    val userPersonaPreference by viewModel.userPersonaPreference.collectAsState()

    var stripSensitive by remember { mutableStateOf(true) }
    var exportFormat by remember { mutableStateOf("JSON Structure") }

    val exportResult = remember(logs, topVocabulary, learnedCorrections, userPersonaPreference, stripSensitive, exportFormat) {
        PersonalModelSerializer.serialize(
            vocabulary = topVocabulary,
            corrections = learnedCorrections,
            logs = logs,
            personaPreference = userPersonaPreference,
            stripSensitive = stripSensitive,
            exportFormat = exportFormat
        )
    }
    val compiledJson = exportResult.serializedContent

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Secure Export Settings Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(1.dp, RoundedCornerShape(24.dp)),
            colors = CardDefaults.cardColors(containerColor = colors.card),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, colors.border)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "🔐 Secure Export Settings",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Configure privacy redactions and formatting before serializing patterns for external personal model training.",
                    color = colors.textSecondary,
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
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            "Redacts emails, phone numbers, financials, IPs, URLs, and private IDs completely offline.",
                            color = colors.textSecondary,
                            fontSize = 10.sp,
                            lineHeight = 12.sp
                        )
                    }
                    Switch(
                        checked = stripSensitive,
                        onCheckedChange = { stripSensitive = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onAccent,
                            checkedTrackColor = colors.accent
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 2. Export Format Selection
                Text(
                    "Export Serialization Format",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("JSON Structure", "Base64 Cipher Block").forEach { format ->
                        ChoiceOptionChip(
                            label = format,
                            selected = exportFormat == format,
                            onClick = { exportFormat = format },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // 3. Redactions Stats Banner (if enabled and redactions found)
                if (stripSensitive && exportResult.stats.totalRedactions > 0) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.successContainer)
                            .border(1.dp, colors.success, RoundedCornerShape(12.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(
                                "🛡️ Privacy Shield Active",
                                color = colors.onSuccessContainer,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                            Text(
                                "Redacted ${exportResult.stats.totalRedactions} sensitive items: " +
                                    listOfNotNull(
                                        if (exportResult.stats.emailsRedacted > 0) "${exportResult.stats.emailsRedacted} email(s)" else null,
                                        if (exportResult.stats.phonesRedacted > 0) "${exportResult.stats.phonesRedacted} phone(s)" else null,
                                        if (exportResult.stats.cardsRedacted > 0) "${exportResult.stats.cardsRedacted} financial token(s)" else null,
                                        if (exportResult.stats.urlsRedacted > 0) "${exportResult.stats.urlsRedacted} link(s)" else null,
                                        if (exportResult.stats.ipAddressesRedacted > 0) "${exportResult.stats.ipAddressesRedacted} IP(s)" else null,
                                        if (exportResult.stats.numericIdsRedacted > 0) "${exportResult.stats.numericIdsRedacted} numeric ID(s)" else null
                                    ).joinToString(", "),
                                color = colors.success,
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
                            .background(colors.surfaceMuted)
                            .padding(10.dp)
                    ) {
                        Text(
                            "✅ No sensitive identifiers detected in typing history.",
                            color = colors.textTertiary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Export Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(1.dp, RoundedCornerShape(28.dp)),
            colors = CardDefaults.cardColors(containerColor = colors.card),
            shape = RoundedCornerShape(28.dp),
            border = BorderStroke(1.dp, colors.border)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Telemetry Style Data (JSON)",
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Export style parameters to train offline custom AI models to replicate your unique writing and vocabulary patterns.",
                    color = colors.textSecondary,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.cardDark)
                        .padding(12.dp)
                ) {
                    LazyColumn {
                        item {
                            Text(
                                text = compiledJson,
                                color = colors.onCardDarkAccent,
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
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("copy_export_button")
                    ) {
                        Text("Copy JSON", color = colors.onAccent, fontWeight = FontWeight.Bold)
                    }

                    Button(
                        onClick = {
                            viewModel.clearLogs()
                            Toast.makeText(context, "Cleared local database writing history!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.danger),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("clear_export_button")
                    ) {
                        Text("Clear Logs", color = colors.onCardDark)
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
                            viewModel.importPersonalModel(clip) { imported ->
                                val message = if (imported < 0) {
                                    "Clipboard does not contain a valid export."
                                } else {
                                    "Imported $imported personalization record(s)!"
                                }
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceMuted),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, colors.borderStrong),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_button")
                ) {
                    Text("Import from Clipboard", color = colors.accent, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
