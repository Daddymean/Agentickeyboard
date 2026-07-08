package com.example

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import com.example.db.ShortcutTemplate
import com.example.ui.AgenticKeyboardLayout
import com.example.ui.KeyboardViewModel
import com.example.ui.KeyboardViewModelFactory
import com.example.ui.RowDefaultsButtonPadding
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.tabs.PlaygroundTab
import com.example.ui.tabs.ShortcutsTab
import com.example.ui.tabs.ExportTab
import com.example.ui.tabs.SetupTab

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as AgenticKeyboardApplication
        val factory = KeyboardViewModelFactory(app.repository)
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

