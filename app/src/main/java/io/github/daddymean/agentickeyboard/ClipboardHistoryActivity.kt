package io.github.daddymean.agentickeyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.db.ClipboardHistoryItem
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.theme.MyApplicationTheme
import io.github.daddymean.agentickeyboard.util.ClipboardCaptureDecision
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryLimits
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryPolicy
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import kotlinx.coroutines.launch

/** Non-exported companion surface for explicit clipboard-history control. */
class ClipboardHistoryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as AgenticKeyboardApplication
        setContent {
            MyApplicationTheme {
                ClipboardHistoryManagerScreen(
                    repository = app.repository,
                    settings = app.settings,
                    onClose = { finish() }
                )
            }
        }
    }
}

@Composable
private fun ClipboardHistoryManagerScreen(
    repository: KeyboardRepository,
    settings: KeyboardSettings,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by repository.allClipboardHistory.collectAsState(initial = emptyList())

    var enabled by remember { mutableStateOf(settings.isClipboardHistoryEnabled) }
    var paused by remember { mutableStateOf(settings.isClipboardHistoryPaused) }
    var retentionDays by remember { mutableIntStateOf(settings.clipboardRetentionDays) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    fun captureCurrent() {
        if (!enabled || paused) {
            feedback = if (!enabled) "Enable history before capturing." else "Resume history before capturing."
            return
        }
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
        if (text == null) {
            feedback = "Clipboard has no plain text to retain."
            return
        }
        when (val decision = ClipboardHistoryPolicy.evaluate(text)) {
            is ClipboardCaptureDecision.Accept -> scope.launch {
                repository.captureClipboard(
                    content = decision.content,
                    contentHash = decision.contentHash,
                    retentionDays = retentionDays
                )
                feedback = "Saved locally. Duplicate clips collapse automatically."
            }
            is ClipboardCaptureDecision.Reject -> {
                feedback = ClipboardHistoryPolicy.rejectionMessage(decision.reason)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Clipboard History",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Optional, local, bounded, and foreground-only.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                TextButton(onClick = onClose) { Text("Done") }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Privacy boundary",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "No background listener. Secure fields never capture. Passwords, one-time codes, payment data, private keys, tokens, recovery phrases, and oversized clips are rejected before storage. History is never sent to cloud AI or analytics.",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SettingSwitchRow(
                        title = "Enable clipboard history",
                        subtitle = "Off by default. The keyboard checks the current text clip only while it is visible.",
                        checked = enabled,
                        onCheckedChange = { value ->
                            enabled = value
                            settings.isClipboardHistoryEnabled = value
                            if (!value) {
                                paused = false
                                settings.isClipboardHistoryPaused = false
                            }
                            feedback = if (value) "Enabled. Use Capture current or reopen the keyboard." else "Disabled. Existing clips remain until cleared."
                        },
                        testTag = "clipboard_history_enabled"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingSwitchRow(
                        title = "Pause new capture",
                        subtitle = "Keeps existing history available without collecting new clips.",
                        checked = paused,
                        enabled = enabled,
                        onCheckedChange = { value ->
                            paused = value
                            settings.isClipboardHistoryPaused = value
                            feedback = if (value) "Paused." else "Capture resumed."
                        },
                        testTag = "clipboard_history_paused"
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "Retention",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Unpinned clips expire after $retentionDays day${if (retentionDays == 1) "" else "s"}. Up to ${ClipboardHistoryLimits.MAX_UNPINNED_ITEMS} recent clips and ${ClipboardHistoryLimits.MAX_PINNED_ITEMS} pins are retained.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1, 7, 30).forEach { days ->
                            if (retentionDays == days) {
                                Button(onClick = {}) { Text("$days d") }
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        retentionDays = days
                                        settings.clipboardRetentionDays = days
                                        scope.launch {
                                            repository.pruneClipboardHistory(retentionDays = days)
                                        }
                                    }
                                ) { Text("$days d") }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { captureCurrent() },
                        enabled = enabled && !paused,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("capture_clipboard_now")
                    ) {
                        Text("Capture current clipboard")
                    }
                    feedback?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Saved clips (${history.size})",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                if (!confirmClear) {
                    TextButton(
                        onClick = { confirmClear = true },
                        enabled = history.isNotEmpty(),
                        modifier = Modifier.testTag("clear_clipboard_history")
                    ) { Text("Clear now") }
                }
            }
        }

        if (confirmClear) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Clear all clipboard history, including pinned clips?",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        repository.clearClipboardHistory()
                                        confirmClear = false
                                        feedback = "Clipboard history cleared."
                                    }
                                },
                                modifier = Modifier.testTag("confirm_clear_clipboard_history")
                            ) { Text("Clear all") }
                            OutlinedButton(onClick = { confirmClear = false }) { Text("Cancel") }
                        }
                    }
                }
            }
        }

        if (history.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = "No clips retained. Enable history and capture a plain-text clipboard item to begin.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(history, key = { it.id }) { item ->
                ClipboardHistoryCard(
                    item = item,
                    onCopy = {
                        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        manager.setPrimaryClip(ClipData.newPlainText("Lumina clipboard history", item.content))
                        scope.launch { repository.recordClipboardUse(item.id) }
                        feedback = "Copied to the system clipboard."
                    },
                    onTogglePin = {
                        scope.launch {
                            val success = repository.setClipboardPinned(item.id, !item.pinned)
                            feedback = when {
                                success -> if (item.pinned) "Unpinned." else "Pinned."
                                !item.pinned -> "Pin limit reached (${ClipboardHistoryLimits.MAX_PINNED_ITEMS})."
                                else -> "Could not update this clip."
                            }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            repository.deleteClipboardItem(item.id)
                            feedback = "Clip deleted."
                        }
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(18.dp)) }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 2.dp, end = 12.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun ClipboardHistoryCard(
    item: ClipboardHistoryItem,
    onCopy: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (item.pinned) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = (if (item.pinned) "📌 " else "") + item.content.replace('\n', ' '),
                    modifier = Modifier.weight(1f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp
                )
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.testTag("delete_clipboard_${item.id}")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete clip")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onCopy, modifier = Modifier.weight(1f)) { Text("Copy") }
                OutlinedButton(
                    onClick = onTogglePin,
                    modifier = Modifier.weight(1f).testTag("pin_clipboard_${item.id}")
                ) { Text(if (item.pinned) "Unpin" else "Pin") }
            }
        }
    }
}
