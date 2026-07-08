package com.example.ui.tabs

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import com.example.ui.KeyboardViewModel
import com.example.util.PersonalModelSerializer
import kotlinx.coroutines.launch
import com.example.ui.RowDefaultsButtonPadding

@Composable
fun ExportTab(viewModel: KeyboardViewModel) {
    val logs by viewModel.logs.collectAsState()
    val topVocabulary by viewModel.topVocabulary.collectAsState()
    val learnedCorrections by viewModel.learnedCorrections.collectAsState()
    val userPersonaPreference by viewModel.userPersonaPreference.collectAsState()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    var stripSensitive by remember { mutableStateOf(true) }
    var exportFormat by remember { mutableStateOf("JSON Structure") }

    val exportResult = remember(logs, topVocabulary, learnedCorrections, userPersonaPreference, stripSensitive, exportFormat) {
        com.example.util.PersonalModelSerializer.serialize(
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
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
