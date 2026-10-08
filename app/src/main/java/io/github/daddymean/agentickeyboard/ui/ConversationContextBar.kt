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
    onConfirm: (List<Int>) -> Unit,
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
                }
                state.status?.let { Text(it, color = colors.textMuted, fontSize = 11.sp) }
                val pending = state.pending
                if (pending != null) {
                    var selected by remember(pending) { mutableStateOf(emptySet<Int>()) }
                    Text("Select the message text you are answering. Screen text may include names or buttons.",
                        color = colors.text, fontSize = 11.sp)
                    if (pending.truncated) Text("Only the last 8,000 characters / 40 visible text blocks are shown.",
                        color = colors.textMuted, fontSize = 10.sp)
                    Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) {
                        pending.lines.forEachIndexed { index, line ->
                            Row {
                                Checkbox(checked = index in selected, onCheckedChange = {
                                    selected = if (it) selected + index else selected - index
                                })
                                Text(line, color = colors.text, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                            }
                        }
                    }
                    TextButton(enabled = selected.isNotEmpty(),
                        onClick = { onConfirm(selected.sorted()) }) { Text("Attach selected text") }
                } else if (state.active) {
                    Text(if (offline) "Context attached • local tools" else
                        "Context attached • AI actions send selected text to Gemini with redaction",
                        color = colors.textMuted, fontSize = 10.sp)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { onAction(ConversationContextAction.REPLY) }) { Text("Reply Ideas") }
                        TextButton(onClick = { onAction(ConversationContextAction.SUMMARY) }) { Text("Summarize") }
                        TextButton(onClick = { onAction(ConversationContextAction.TRANSLATE) }) { Text("Translate") }
                        TextButton(onClick = { onAction(ConversationContextAction.EXPLAIN) }) { Text("Explain") }
                    }
                }
            }
        }
    }
}
