package io.github.daddymean.agentickeyboard

import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.lifecycle.ViewModelProvider
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModelFactory
import io.github.daddymean.agentickeyboard.ui.theme.CompanionTheme
import io.github.daddymean.agentickeyboard.ui.theme.LocalCompanionColors
import io.github.daddymean.agentickeyboard.ui.theme.resolveDarkTheme

class MainActivity : ComponentActivity() {
    companion object {
        /** Int extra: bottom-navigation tab to open (see MainAppScreen). */
        const val EXTRA_TAB = "io.github.daddymean.agentickeyboard.extra.TAB"
        /** "Style Hub" tab, which holds the Keyboard Settings card. */
        const val TAB_SETTINGS = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as AgenticKeyboardApplication
        val factory = KeyboardViewModelFactory(app.repository, app.settings)
        val viewModel = ViewModelProvider(this, factory)[KeyboardViewModel::class.java]

        setContent {
            val themeOverride by viewModel.themeOverride.collectAsState()
            val darkTheme = resolveDarkTheme(themeOverride, isSystemInDarkTheme())
            CompanionTheme(darkTheme = darkTheme) {
                MainAppScreen(viewModel, initialTab = intent.getIntExtra(EXTRA_TAB, 0))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(viewModel: KeyboardViewModel, initialTab: Int = 0) {
    var selectedTab by remember { mutableStateOf(initialTab.coerceIn(0, 3)) }
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()
    val colors = LocalCompanionColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Lumina AI Keyboard",
                            color = colors.textPrimary,
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
                                    .background(if (isOfflineMode) colors.success else colors.accent)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (isOfflineMode) "ON-DEVICE ACTIVE (🔒 PRIVATE)" else "CLOUD AI ASSIST ACTIVE",
                                color = colors.textSecondary,
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
                            .background(colors.card)
                            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                    ) {
                        Icon(
                            imageVector = if (isOfflineMode) Icons.Default.Lock else Icons.Default.Settings,
                            contentDescription = "Toggle Privacy",
                            tint = colors.accent,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    scrolledContainerColor = colors.background
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = colors.background,
                tonalElevation = 8.dp,
                modifier = Modifier.border(BorderStroke(1.dp, colors.border))
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
                            selectedIconColor = colors.accent,
                            unselectedIconColor = colors.textSecondary,
                            selectedTextColor = colors.accent,
                            unselectedTextColor = colors.textSecondary,
                            indicatorColor = colors.cardAccent
                        ),
                        modifier = Modifier.testTag("nav_item_$index")
                    )
                }
            }
        },
        containerColor = colors.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> PlaygroundTab(viewModel) { selectedTab = 1 }
                1 -> ShortcutsTab(viewModel)
                2 -> ExportTab(viewModel)
                3 -> SetupTab(viewModel)
            }
        }
    }
}
