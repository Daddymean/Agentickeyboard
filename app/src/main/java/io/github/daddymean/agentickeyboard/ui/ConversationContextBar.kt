package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.ui.theme.KeyboardTheme
import io.github.daddymean.agentickeyboard.ui.theme.LocalKeyboardColors
import io.github.daddymean.agentickeyboard.util.VisibleContext
import io.github.daddymean.agentickeyboard.util.ReplyIntents

data class ConversationContextUiState(
    val pending: VisibleContext? = null,
    val active: Boolean = false,
    val status: String? = null
)

enum class ConversationContextAction { REPLY, SUMMARY, TRANSLATE, EXPLAIN }

/** No text is attached or sent from capture alone. Selection is explicit. */
@Composable
fun ConversationContextBar(
    state: ConversationContextUiState,
    themeOverride: String,
    offline: Boolean,
    onCapture: () -> Unit,
    onSettings: () -> Unit,
    onClear: () -> Unit,
    onAction: (ConversationContextAction) -> Unit
) {
    KeyboardTheme(darkTheme = when (themeOverride) {
        "Light" -> false
        "Dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }) {
        val colors = LocalKeyboardColors.current
        Surface(color = colors.shelf, modifier = Modifier.fillMaxWidth().testTag("conversation_context_bar")) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = onCapture) { Text("Use conversation") }
                    TextButton(onClick = onSettings) { Text("Capture settings") }
                    if (state.pending != null || state.active) {
                        TextButton(onClick = onClear) { Text("Clear") }
                    }
                    if (state.active) {
                        TextButton(onClick = { onAction(ConversationContextAction.REPLY) }) { Text("Reply Ideas") }
                        TextButton(onClick = { onAction(ConversationContextAction.SUMMARY) }) { Text("Summarize") }
                        TextButton(onClick = { onAction(ConversationContextAction.TRANSLATE) }) { Text("Translate") }
                        TextButton(onClick = { onAction(ConversationContextAction.EXPLAIN) }) { Text("Explain") }
                    }
                }
                Text(state.status ?: if (state.active) {
                    if (offline) "Context attached • local tools" else
                        "AI actions send selected text to Gemini with redaction"
                } else "Capture visible text, then select the message you are answering.",
                    color = colors.textMuted, fontSize = 10.sp, maxLines = 2,
                    modifier = Modifier.height(28.dp))
            }
        }
    }
}

/** Context intent/loading/results use the same key-area bounds as the preview. */
@Composable
fun ConversationContextResult(
    panel: AiPanelState,
    themeOverride: String,
    onIntent: (String?) -> Unit,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit
) {
    KeyboardTheme(darkTheme = when (themeOverride) {
        "Light" -> false
        "Dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }) {
        val colors = LocalKeyboardColors.current
        Surface(onClick = {}, color = colors.panel, modifier = Modifier.fillMaxSize()
            .testTag("conversation_context_result")) {
            Column(Modifier.padding(8.dp)) {
                Row {
                    Text("Conversation tools", color = colors.text, fontSize = 12.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Back to typing") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    when (panel) {
                        is AiPanelState.ReplyIntent -> {
                            Text("Reply with which intent?", color = colors.text)
                            TextButton(onClick = { onIntent(null) }) { Text("Auto") }
                            ReplyIntents.ALL.forEach { intent ->
                                TextButton(onClick = { onIntent(intent) }) { Text(intent) }
                            }
                        }
                        is AiPanelState.Replies -> panel.suggestions.forEach { reply ->
                            TextButton(onClick = { onInsert(reply) }) { Text(reply) }
                        }
                        AiPanelState.Loading -> Text("Working…", color = colors.text)
                        else -> {
                            val text = when (panel) {
                                is AiPanelState.Summary -> panel.text
                                is AiPanelState.Translation -> panel.text
                                is AiPanelState.Explanation -> panel.text
                                else -> null
                            }
                            text?.let {
                                Text(it, color = colors.text, fontSize = 12.sp)
                                TextButton(onClick = { onInsert(it) }) { Text("Insert into draft") }
                            }
                        }
                    }
                }
            }
        }
    }
}


/** Overlays the measured key area so preview never changes the host's viewport. */
@Composable
fun ConversationContextPreview(
    pending: VisibleContext,
    themeOverride: String,
    onConfirm: (List<Int>) -> Unit,
    onClear: () -> Unit
) {
    KeyboardTheme(darkTheme = when (themeOverride) {
        "Light" -> false
        "Dark" -> true
        else -> androidx.compose.foundation.isSystemInDarkTheme()
    }) {
        val colors = LocalKeyboardColors.current
        var selected by remember(pending) { mutableStateOf(emptySet<Int>()) }
        Surface(onClick = {}, color = colors.panel, modifier = Modifier.fillMaxSize()
            .testTag("conversation_context_preview")) {
            Column(Modifier.padding(8.dp)) {
                Text("Select message text. Names and app controls may also appear.",
                    color = colors.text, fontSize = 11.sp)
                if (pending.truncated) Text("Last 8,000 characters / 40 visible text blocks only.",
                    color = colors.textMuted, fontSize = 10.sp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    pending.lines.forEachIndexed { index, line ->
                        Row {
                            Checkbox(checked = index in selected, onCheckedChange = {
                                selected = if (it) selected + index else selected - index
                            }, modifier = Modifier.testTag("context_block_$index"))
                            Text(line, color = colors.text, fontSize = 12.sp,
                                modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                        }
                    }
                }
                Row {
                    TextButton(enabled = selected.isNotEmpty(),
                        onClick = { onConfirm(selected.sorted()) }) { Text("Attach selected text") }
                    TextButton(onClick = onClear) { Text("Cancel") }
                }
            }
        }
    }
}
