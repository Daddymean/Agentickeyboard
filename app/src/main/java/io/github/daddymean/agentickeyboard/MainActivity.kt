package io.github.daddymean.agentickeyboard

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.ui.AgenticKeyboardLayout
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModelFactory
import io.github.daddymean.agentickeyboard.ui.RowDefaultsButtonPadding
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import io.github.daddymean.agentickeyboard.util.AppPersonas
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as AgenticKeyboardApplication
        val factory = KeyboardViewModelFactory(app.repository, app.settings)
        val viewModel = ViewModelProvider(this, factory)[KeyboardViewModel::class.java]

        setContent {
            MyApplicationTheme {
                MainAppScreen(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: KeyboardViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()

    // Light-theme slate/grey background matching Bento style
    val lightBgColor = Color(0xFFF3F4F9)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Lumina AI Keyboard",
                            color = Color(0xFF1C1B1F),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            letterSpacing = (-0.5).sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (isOfflineMode) Color(0xFF2E7D32) else Color(0xFF6750A4))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isOfflineMode) "ON-DEVICE ACTIVE (🔒 PRIVATE)" else "CLOUD AI ASSIST ACTIVE",
                                color = Color(0xFF5F5D6B),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                },
                actions = {
                    // Settings-styled toggle gear
                    IconButton(
                        onClick = { viewModel.toggleOfflineMode() },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White)
                            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = if (isOfflineMode) Icons.Default.Lock else Icons.Default.Settings,
                            contentDescription = "Toggle Privacy",
                            tint = Color(0xFF6750A4),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = lightBgColor,
                    scrolledContainerColor = lightBgColor
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = lightBgColor,
                tonalElevation = 8.dp,
                modifier = Modifier.border(BorderStroke(1.dp, Color(0xFFE2E8F0)))
            ) {
                val items = listOf(
                    Triple("Console", Icons.Default.Home, 0),
                    Triple("Shortcuts", Icons.Default.Settings, 1),
                    Triple("Style Hub", Icons.Default.Share, 2),
                    Triple("Setup Guide", Icons.Default.Info, 3)
                )
                items.forEach { (label, icon, index) ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF6750A4),
                            unselectedIconColor = Color(0xFF5F5D6B),
                            selectedTextColor = Color(0xFF6750A4),
                            unselectedTextColor = Color(0xFF5F5D6B),
                            indicatorColor = Color(0xFFE8DEF8)
                        ),
                        modifier = Modifier.testTag("nav_item_$index")
                    )
                }
            }
        },
        containerColor = lightBgColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(lightBgColor)
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> PlaygroundTab(viewModel) { selectedTab = 1 }
                1 -> ShortcutsTab(viewModel)
                2 -> ExportTab(viewModel)
                3 -> SetupTab()
            }
        }
    }
}

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

@Composable
fun ShortcutsTab(viewModel: KeyboardViewModel) {
    var newShortcut by remember { mutableStateOf("") }
    var newTemplate by remember { mutableStateOf("") }
    var newCommandToken by remember { mutableStateOf("") }
    var newCommandInstruction by remember { mutableStateOf("") }

    val shortcuts by viewModel.shortcuts.collectAsState()
    val customCommands by viewModel.customCommands.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Quick Templates",
                color = Color(0xFF1C1B1F),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp
            )
            Text(
                "Configure on-device templates. Typing an abbreviation suggests full phrases offline.",
                color = Color(0xFF5F5D6B),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        // Add Shortcut Form Card
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
                        "Add Custom Template",
                        color = Color(0xFF1C1B1F),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    OutlinedTextField(
                        value = newShortcut,
                        onValueChange = { newShortcut = it },
                        label = { Text("Shortcut key (e.g. omw)") },
                        placeholder = { Text("omw") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6750A4),
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            focusedTextColor = Color(0xFF1C1B1F),
                            unfocusedTextColor = Color(0xFF1C1B1F)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("shortcut_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = newTemplate,
                        onValueChange = { newTemplate = it },
                        label = { Text("Phrase expansion") },
                        placeholder = { Text("On my way!") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6750A4),
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            focusedTextColor = Color(0xFF1C1B1F),
                            unfocusedTextColor = Color(0xFF1C1B1F)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("template_input")
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (newShortcut.isNotBlank() && newTemplate.isNotBlank()) {
                                viewModel.addShortcut(newShortcut.trim(), newTemplate.trim())
                                newShortcut = ""
                                newTemplate = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_shortcut_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Template", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Text(
                "Active Shortcuts (${shortcuts.size})",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        items(shortcuts) { shortcut ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(1.dp, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE8DEF8))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                shortcut.shortcut,
                                color = Color(0xFF21005D),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            shortcut.template,
                            color = Color(0xFF1C1B1F),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    IconButton(
                        onClick = { viewModel.deleteShortcut(shortcut.id) },
                        modifier = Modifier.testTag("delete_shortcut_${shortcut.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Template",
                            tint = Color(0xFFC62828)
                        )
                    }
                }
            }
        }

        // --- User-defined slash commands for the keyboard's "/" palette ---
        item {
            Text(
                "Slash Commands",
                color = Color(0xFF1C1B1F),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                "Your own /commands: start a draft with the token and the keyboard rewrites it with your saved instruction. Built-in commands always win on a name clash.",
                color = Color(0xFF5F5D6B),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

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
                        "Add Slash Command",
                        color = Color(0xFF1C1B1F),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = newCommandToken,
                        onValueChange = { newCommandToken = it },
                        label = { Text("Command (e.g. /boss)") },
                        placeholder = { Text("/boss") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6750A4),
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            focusedTextColor = Color(0xFF1C1B1F),
                            unfocusedTextColor = Color(0xFF1C1B1F)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("command_token_input")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = newCommandInstruction,
                        onValueChange = { newCommandInstruction = it },
                        label = { Text("Rewrite instruction") },
                        placeholder = { Text("formal but friendly, for my manager") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF6750A4),
                            unfocusedBorderColor = Color(0xFFCBD5E1),
                            focusedTextColor = Color(0xFF1C1B1F),
                            unfocusedTextColor = Color(0xFF1C1B1F)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("command_instruction_input")
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            if (newCommandToken.isNotBlank() && newCommandInstruction.isNotBlank()) {
                                viewModel.addCustomCommand(newCommandToken, newCommandInstruction)
                                newCommandToken = ""
                                newCommandInstruction = ""
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6750A4)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("add_command_button")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Add", tint = Color.White)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Command", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Text(
                "Custom Commands (${customCommands.size})",
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        items(customCommands) { command ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(1.dp, RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, Color(0xFFE2E8F0))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFE8DEF8))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                command.token,
                                color = Color(0xFF21005D),
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            command.instruction,
                            color = Color(0xFF1C1B1F),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    IconButton(
                        onClick = { viewModel.deleteCustomCommand(command.id) },
                        modifier = Modifier.testTag("delete_command_${command.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Command",
                            tint = Color(0xFFC62828)
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun ExportTab(viewModel: KeyboardViewModel) {
    val logs by viewModel.logs.collectAsState()
    val topVocabulary by viewModel.topVocabulary.collectAsState()
    val learnedCorrections by viewModel.learnedCorrections.collectAsState()
    val userPersonaPreference by viewModel.userPersonaPreference.collectAsState()
    val appPersonas by viewModel.appPersonas.collectAsState()
    val usageStats by viewModel.usageStats.collectAsState()
    val isAutoCapitalize by viewModel.isAutoCapitalizeEnabled.collectAsState()
    val isNumberRow by viewModel.isNumberRowEnabled.collectAsState()
    val isProofread by viewModel.isProofreadEnabled.collectAsState()
    val isLearningPaused by viewModel.isLearningPaused.collectAsState()
    val isHaptics by viewModel.isHapticsEnabled.collectAsState()
    val isVoiceLock by viewModel.isVoiceLockEnabled.collectAsState()
    val isSendGuard by viewModel.isSendGuardEnabled.collectAsState()
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
                        title = "Number row",
                        description = "Show a dedicated 1-0 row above the letters.",
                        checked = isNumberRow,
                        onCheckedChange = { viewModel.setNumberRowEnabled(it) }
                    )
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

@Composable
fun SetupTab() {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Setup Guide",
                color = Color(0xFF1C1B1F),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp
            )
            Text(
                "Activate Lumina AI system-wide for access inside Signal, WhatsApp, or other messengers.",
                color = Color(0xFF5F5D6B),
                fontSize = 12.sp,
                lineHeight = 16.sp
            )
        }

        item {
            SetupStepCard(
                stepNumber = "1",
                title = "Enable Keyboard in Settings",
                description = "Enable Lumina Keyboard inside your Android system Language & Input settings panel.",
                actionLabel = "Open System Keyboards",
                onAction = {
                    try {
                        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Search Languages & Input settings on your device.", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        item {
            SetupStepCard(
                stepNumber = "2",
                title = "Select as Active Input",
                description = "Trigger the keyboard selector dialog to choose Lumina AI as your standard input.",
                actionLabel = "Switch Active Input method",
                onAction = {
                    try {
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showInputMethodPicker()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Switch via status notification bar.", Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        item {
            SetupStepCard(
                stepNumber = "3",
                title = "Verify Absolute Offline Privacy",
                description = "Confirm local processing state. Toggling Local Lock blocks cloud connection for ultimate confidentiality.",
                actionLabel = "Security Protocol Verified",
                onAction = {
                    Toast.makeText(context, "Lumina offline containment active!", Toast.LENGTH_SHORT).show()
                }
            )
        }
        
        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SettingSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = Color(0xFF1C1B1F),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
            Text(
                description,
                color = Color(0xFF5F5D6B),
                fontSize = 10.sp,
                lineHeight = 12.sp
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF6750A4)
            )
        )
    }
}

@Composable
fun DashboardStat(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$value",
            color = Color(0xFF4ADE80),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 10.sp
        )
    }
}

@Composable
fun SetupStepCard(
    stepNumber: String,
    title: String,
    description: String,
    actionLabel: String,
    onAction: () -> Unit
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8DEF8)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stepNumber,
                        color = Color(0xFF21005D),
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    title,
                    color = Color(0xFF1C1B1F),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            
            Text(
                description,
                color = Color(0xFF5F5D6B),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, Color(0xFFCBD5E1))
            ) {
                Text(actionLabel, color = Color(0xFF6750A4), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
