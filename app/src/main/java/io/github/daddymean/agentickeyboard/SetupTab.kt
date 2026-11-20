package io.github.daddymean.agentickeyboard

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel
import io.github.daddymean.agentickeyboard.util.KeyboardSetupStatus

/** Reads whether this keyboard is enabled and active; see [KeyboardSetupStatus]. */
private fun readKeyboardSetupStatus(context: Context): KeyboardSetupStatus {
    val enabled = runCatching {
        context.getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList?.map { it.packageName }
    }.getOrNull().orEmpty()
    val defaultIme = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    }.getOrNull()
    return KeyboardSetupStatus.of(context.packageName, enabled, defaultIme)
}

@Composable
fun SetupTab(viewModel: KeyboardViewModel) {
    val context = LocalContext.current
    val isOfflineMode by viewModel.isOfflineMode.collectAsState()

    // Both setup actions leave this window (system settings, the picker dialog) and
    // come back to it, so re-read the status each time the window regains focus.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var setupStatus by remember { mutableStateOf(readKeyboardSetupStatus(context)) }
    LaunchedEffect(windowFocused) {
        if (windowFocused) setupStatus = readKeyboardSetupStatus(context)
    }

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
                description = if (setupStatus.enabled) {
                    "Done: Lumina Keyboard is enabled in your system keyboard settings."
                } else {
                    "Enable Lumina Keyboard inside your Android system Language & Input settings panel."
                },
                actionLabel = "Open System Keyboards",
                done = setupStatus.enabled,
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
                description = if (setupStatus.selected) {
                    "Done: Lumina AI is your active keyboard."
                } else {
                    "Trigger the keyboard selector dialog to choose Lumina AI as your standard input."
                },
                actionLabel = "Switch Active Input method",
                done = setupStatus.selected,
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
            // Reflects the real setting. This step used to report "offline containment
            // active" on every tap, even with offline mode off and cloud AI in use.
            SetupStepCard(
                stepNumber = "3",
                title = "Offline Mode (optional)",
                description = if (isOfflineMode) {
                    "On: AI features run on this device only and nothing is sent to the cloud."
                } else {
                    "Off: cloud AI features send your text to Gemini, with phone numbers, emails " +
                        "and similar values redacted first. Turn on to keep all AI on this device."
                },
                actionLabel = if (isOfflineMode) "Turn Offline Mode Off" else "Turn Offline Mode On",
                done = isOfflineMode,
                onAction = { viewModel.toggleOfflineMode() }
            )
        }

        item {
            // The only route to the clipboard manager used to be a chip on the
            // keyboard's own clipboard bar, which is hidden while the feature is off.
            // That left the default-off state with no way in at all, so the companion
            // app carries a permanent entry point.
            SetupStepCard(
                stepNumber = "4",
                title = "Clipboard History (optional)",
                description = "Off by default. Open the manager to turn on local clipboard history, review what is retained, or clear it.",
                actionLabel = "Open Clipboard History",
                onAction = {
                    runCatching {
                        context.startActivity(Intent(context, ClipboardHistoryActivity::class.java))
                    }.onFailure {
                        Toast.makeText(
                            context,
                            "Unable to open Clipboard History.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }

        item {
            // The Snippet Vault bar only renders once the draft already starts with
            // a recall command, so its Manage chip could only ever be found by
            // someone who knew to type "/v" in the first place. That left the
            // manager — and the feature itself — with no way in for everyone else.
            SetupStepCard(
                stepNumber = "5",
                title = "Snippet Vault (optional)",
                description = "Save reusable text, then recall it from the keyboard by typing /v followed by a search term. Open the manager to add your first snippet.",
                actionLabel = "Open Snippet Vault",
                onAction = {
                    runCatching {
                        context.startActivity(Intent(context, SnippetVaultActivity::class.java))
                    }.onFailure {
                        Toast.makeText(
                            context,
                            "Unable to open Snippet Vault.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }

        item {
            // KEYBOARD-024: the legacy twin learns by default, so its notice,
            // viewer and delete controls need a permanent way in.
            SetupStepCard(
                stepNumber = "6",
                title = "Legacy twin (on)",
                description = "Learns how you write from messages you type and send, encrypted on this phone only. Never from password or incognito fields. Open to read the privacy notice, view or delete entries, pause, or turn it off.",
                actionLabel = "Open Legacy twin",
                onAction = {
                    runCatching {
                        context.startActivity(Intent(context, TwinActivity::class.java))
                    }.onFailure {
                        Toast.makeText(
                            context,
                            "Unable to open Legacy twin.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }

        item {
            // KEYBOARD-047 (Keith, 2026-11-19, #230 item 9): the keyboard's own learned data
            // gets its own viewer and delete screen, separate from the twin's.
            SetupStepCard(
                stepNumber = "7",
                title = "What the keyboard has learned",
                description = "See and delete what the keyboard learned from your typing: words, next-word predictions, auto-correct rules, the writing log, per-app styles, clipboard history and touch calibration. On this phone only.",
                actionLabel = "Open learned data",
                onAction = {
                    runCatching {
                        context.startActivity(Intent(context, KeyboardLearnedActivity::class.java))
                    }.onFailure {
                        Toast.makeText(
                            context,
                            "Unable to open learned data.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            )
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
