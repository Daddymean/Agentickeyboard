package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.db.ClipboardHistoryItem
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.theme.LocalKeyboardColors

/**
 * Compact, local-only clipboard history surface. It never reads the clipboard;
 * the foreground IME service owns capture and passes explicit callbacks here.
 */
@Composable
fun ClipboardHistoryBar(
    repository: KeyboardRepository,
    enabled: Boolean,
    paused: Boolean,
    sensitiveField: Boolean,
    statusMessage: String?,
    onEnable: () -> Unit,
    onTogglePause: () -> Unit,
    onCaptureCurrent: () -> Unit,
    onInsert: (ClipboardHistoryItem) -> Unit,
    onOpenManager: () -> Unit
) {
    if (sensitiveField) return

    val colors = LocalKeyboardColors.current
    // Do not open or collect the optional Room table merely to show the opt-in
    // control. This keeps the keyboard startup path independent of clipboard
    // history until the user actually enables it.
    val history = if (enabled) {
        val collected by repository.allClipboardHistory.collectAsState(initial = emptyList())
        collected
    } else {
        emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.panel)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag("clipboard_history_bar")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        !enabled -> "📋 Clipboard history is off"
                        paused -> "⏸ Clipboard history paused"
                        else -> "📋 Local clipboard history"
                    },
                    color = colors.text,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = statusMessage ?: when {
                        !enabled -> "Nothing is retained until you opt in."
                        paused -> "Existing clips remain; new capture is stopped."
                        else -> "Foreground only • sensitive clips rejected"
                    },
                    color = colors.textMuted,
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
            if (!enabled) {
                ClipboardBarChip("Enable", onEnable, "enable_clipboard_history")
            } else {
                ClipboardBarChip(
                    label = if (paused) "Resume" else "Pause",
                    onClick = onTogglePause,
                    testTag = "toggle_clipboard_history_pause"
                )
                Spacer(modifier = Modifier.width(4.dp))
                ClipboardBarChip("Capture", onCaptureCurrent, "capture_current_clipboard")
            }
            Spacer(modifier = Modifier.width(4.dp))
            ClipboardBarChip("Manage", onOpenManager, "manage_clipboard_history")
        }

        if (enabled && history.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().testTag("clipboard_history_items")
            ) {
                items(history, key = { it.id }) { item ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (item.pinned) colors.successChip else colors.chip)
                            .clickable { onInsert(item) }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                            .testTag("clipboard_item_${item.id}")
                    ) {
                        Text(
                            text = (if (item.pinned) "📌 " else "") + preview(item.content),
                            color = colors.onChip,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipboardBarChip(label: String, onClick: () -> Unit, testTag: String) {
    val colors = LocalKeyboardColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(colors.chipAlt)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            color = colors.onChip,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun preview(content: String): String {
    val compact = content.replace('\n', ' ').trim()
    return if (compact.length <= 42) compact else compact.take(41) + "…"
}
