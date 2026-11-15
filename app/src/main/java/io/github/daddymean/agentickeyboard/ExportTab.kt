package io.github.daddymean.agentickeyboard

import android.content.Context
import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel
import io.github.daddymean.agentickeyboard.ui.KeyboardMasteryCard
import io.github.daddymean.agentickeyboard.ui.GeminiApiKeyCard
import io.github.daddymean.agentickeyboard.ui.RowDefaultsButtonPadding
import io.github.daddymean.agentickeyboard.util.AppPersonas
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.LearnedRuleCleanup
import io.github.daddymean.agentickeyboard.ondevice.GemmaSettingsSection
import io.github.daddymean.agentickeyboard.util.LatencyMeter
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus

@Composable
fun ExportTab(viewModel: KeyboardViewModel) {
    val logs by viewModel.logs.collectAsState()
    val topVocabulary by viewModel.topVocabulary.collectAsState()
    val learnedCorrections by viewModel.learnedCorrections.collectAsState()
    val userPersonaPreference by viewModel.userPersonaPreference.collectAsState()
    val appPersonas by viewModel.appPersonas.collectAsState()
    val usageStats by viewModel.usageStats.collectAsState()
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
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    var stripSensitive by remember { mutableStateOf(true) }
    var exportFormat by remember { mutableStateOf("JSON Structure") }
    var retentionDays by remember { mutableStateOf(viewModel.getLogRetentionDays()) }

    val exportResult = remember(logs, topVocabulary, learnedCorrections, userPersonaPreference, stripSensitive, exportFormat) {
        io.github.daddymean.agentickeyboard.util.PersonalModelSerializer.serialize(
            vocabulary = topVocabulary,
            corrections = learnedCorrections,
            logs = logs,
            personaPreference = userPersonaPreference,
            stripSensitive = stripSensitive,
            exportFormat = exportFormat
        )
    }

    val compiledJson = exportResult.serializedContent

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Opened directly by the keyboard's settings gear, so these come first.
        item { GeminiApiKeyCard() }
        // Keyboard behavior settings (persisted across restarts)
        item {
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
                        onCheckedChange = { viewModel.setAutoCapitalizeEnabled(it) }
                    )
                    SettingSwitchRow(
                        title = "Auto-fix typos on space",
                        description = "Fixes a clear typo when you press space; ⌫ right after puts it back. Never touches names, slang or your own words.",
                        checked = isAutoFix,
                        onCheckedChange = { viewModel.setAutoFixEnabled(it) }
                    )
                    var haggleOn by remember { mutableStateOf(viewModel.isHaggleModeEnabled()) }
                    var haggleStatus by remember { mutableStateOf("") }
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
                        if (haggleStatus.isNotEmpty()) Text(haggleStatus, fontSize = 10.sp, color = Color(0xFF5F5D6B))
                    }
                    var toneOut by remember { mutableStateOf(viewModel.isToneMirrorOutgoing()) }
                    var toneIn by remember { mutableStateOf(viewModel.isToneMirrorIncoming()) }
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
                        Text("Key size", color = Color(0xFF1C1B1F), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "Taller keys and bigger letters for easier typing.",
                            color = Color(0xFF79747E),
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            KeyboardSettings.KEY_SIZE_PRESETS.forEach { (label, scale) ->
                                val selected = kotlin.math.abs(keyHeightScale - scale) < 0.01f
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(if (selected) Color(0xFF6750A4) else Color(0xFFF3F4F9))
                                        .clickable { viewModel.setKeyHeightScale(scale) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                        .testTag("key_size_$label")
                                ) {
                                    Text(
                                        label,
                                        color = if (selected) Color.White else Color(0xFF1C1B1F),
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
                        description = "Incognito for the personalization engine: stop learning vocabulary, word pairs, and corrections.",
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
                    val themeOverride by viewModel.themeOverride.collectAsState()
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
                        KeyboardViewModel.THEME_MODES.forEach { mode ->
                            val isSelected = themeOverride == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) Color(0xFFE8DEF8) else Color(0xFFF1F5F9))
                                    .clickable { viewModel.setThemeOverride(mode) }
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
                    val onDeviceAiStatus by (context.applicationContext as AgenticKeyboardApplication)
                        .onDeviceAi.status.collectAsState()
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
                    GemmaSettingsSection()

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
                                    .clickable {
                                        retentionDays = days
                                        viewModel.setLogRetentionDays(days)
                                    }
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
        item {
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

        item {
            KeyboardMasteryCard(viewModel)
        }

        // Persona Selection Card
        item {
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
                                    .clickable { viewModel.setUserPersonaPreference(persona) }
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
                                    .clickable { viewModel.setUserPersonaPreference(persona) }
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

        // Per-app persona mappings (learned automatically, managed here)
        item {
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

        // Local usage dashboard
        item {
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
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "⏱ TYPING SPEED, RECENT (TYPICAL / SLOW, MS)",
                        color = Color.White.copy(alpha = 0.6f),
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

        // On-Device Statistics Bento Cards
        item {
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
                            "${topVocabulary.size} words",
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
                            "${learnedCorrections.size} rules",
                            color = Color(0xFF1C1B1F),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Personalized Vocabulary Card
        item {
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
                                viewModel.clearVocabulary()
                                Toast.makeText(context, "Cleared learned vocabulary!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFFC62828)),
                            contentPadding = RowDefaultsButtonPadding,
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

        // On-Device Learned Auto-Correction Rules Card
        item {
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
                                viewModel.clearCorrections()
                                Toast.makeText(context, "Cleared spelling corrections!", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFFC62828)),
                            contentPadding = RowDefaultsButtonPadding,
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
                    // KEYBOARD-008: rules removed by the one-time cleanup can be put back.
                    val cleanupPrefs = remember {
                        context.getSharedPreferences(LearnedRuleCleanup.PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    }
                    var removedRuleCount by remember { mutableStateOf(LearnedRuleCleanup.backupCount(cleanupPrefs)) }
                    if (removedRuleCount > 0) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "An update removed $removedRuleCount rules that changed real words (for example your → youre).",
                            color = Color(0xFF5F5D6B),
                            fontSize = 11.sp
                        )
                        Button(
                            onClick = {
                                viewModel.restoreCleanedUpCorrections(cleanupPrefs) { restored ->
                                    removedRuleCount = 0
                                    Toast.makeText(context, "Restored $restored rules", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color(0xFF2563EB)),
                            contentPadding = RowDefaultsButtonPadding,
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("Restore removed rules ($removedRuleCount)", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
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
                                    onClick = { viewModel.deleteCorrection(item.id) },
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

        // Secure Export Settings Card
        item {
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
                            onCheckedChange = { stripSensitive = it },
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
                                    .clickable { exportFormat = format }
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
                    if (stripSensitive && exportResult.stats.totalRedactions > 0) {
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
                                    "Redacted ${exportResult.stats.totalRedactions} sensitive items: " +
                                    listOfNotNull(
                                        if (exportResult.stats.emailsRedacted > 0) "${exportResult.stats.emailsRedacted} email(s)" else null,
                                        if (exportResult.stats.phonesRedacted > 0) "${exportResult.stats.phonesRedacted} phone(s)" else null,
                                        if (exportResult.stats.cardsRedacted > 0) "${exportResult.stats.cardsRedacted} financial token(s)" else null,
                                        if (exportResult.stats.urlsRedacted > 0) "${exportResult.stats.urlsRedacted} link(s)" else null,
                                        if (exportResult.stats.ipAddressesRedacted > 0) "${exportResult.stats.ipAddressesRedacted} IP(s)" else null,
                                        if (exportResult.stats.numericIdsRedacted > 0) "${exportResult.stats.numericIdsRedacted} numeric ID(s)" else null
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

        // Export Card
        item {
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
                                viewModel.clearLogs()
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

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
