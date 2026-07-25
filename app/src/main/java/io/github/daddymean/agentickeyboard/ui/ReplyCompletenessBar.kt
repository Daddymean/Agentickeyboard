package io.github.daddymean.agentickeyboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.daddymean.agentickeyboard.ui.theme.KeyboardTheme
import io.github.daddymean.agentickeyboard.ui.theme.LocalKeyboardColors
import io.github.daddymean.agentickeyboard.util.ReplyCompletenessSession

/**
 * Explicit reply-context capture and advisory controls shown above the keyboard.
 * The clipboard is read only when the user taps the capture action.
 */
@Composable
fun ReplyCompletenessBar(
    viewModel: KeyboardViewModel,
    session: ReplyCompletenessSession,
    onSendAnyway: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    val uiState by session.state.collectAsState()
    val isSensitiveField by viewModel.isSensitiveField.collectAsState()
    val draft by viewModel.inputText.collectAsState()
    val themeOverride by viewModel.themeOverride.collectAsState()
    var feedback by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isSensitiveField) {
        if (isSensitiveField) session.clear()
    }
    LaunchedEffect(draft) {
        session.onDraftChanged(draft)
    }

    if (isSensitiveField) return

    KeyboardTheme(
        darkTheme = when (themeOverride) {
            "Light" -> false
            "Dark" -> true
            else -> isSystemInDarkTheme()
        }
    ) {
        val colors = LocalKeyboardColors.current
        val warning = uiState.warning

        Surface(
            modifier = modifier
                .fillMaxWidth()
                .testTag("reply_completeness_bar"),
            color = if (warning != null) colors.panel else colors.shelf
        ) {
            if (warning != null) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "⚠️ Reply may be incomplete",
                        color = colors.error,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = warning.advisory ?: "Check whether every request was answered.",
                        color = colors.text,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("reply_completeness_advisory")
                    )
                    Text(
                        text = "Edit and recheck, dismiss this draft, or send as-is.",
                        color = colors.textMuted,
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 5.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReplyCoachChip(
                            label = "✏️ Keep editing",
                            modifier = Modifier.testTag("reply_completeness_keep_editing")
                        ) {
                            session.keepEditing()
                        }
                        ReplyCoachChip(
                            label = "Dismiss this draft",
                            modifier = Modifier.testTag("reply_completeness_dismiss")
                        ) {
                            session.dismissWarning()
                        }
                        ReplyCoachChip(
                            label = "Send anyway",
                            emphasized = true,
                            modifier = Modifier.testTag("reply_completeness_send_anyway")
                        ) {
                            session.allowNextSend()
                            onSendAnyway()
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "↩ Reply Coach",
                            color = colors.text,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = feedback ?: when {
                                uiState.hasContext -> buildString {
                                    append(uiState.contextPreview)
                                    if (uiState.contextWasTruncated) append("  •  first 8,000 characters")
                                }
                                else -> "Copy the message you are answering, then attach it here."
                            },
                            color = if (feedback != null) colors.error else colors.textMuted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("reply_context_preview")
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    ReplyCoachChip(
                        label = if (uiState.hasContext) "Replace context" else "Use clipboard",
                        emphasized = uiState.hasContext,
                        modifier = Modifier.testTag("capture_reply_context")
                    ) {
                        val clipboardText = clipboardManager.getText()?.text.orEmpty()
                        feedback = if (session.setIncomingContext(clipboardText)) {
                            null
                        } else {
                            "Clipboard is empty"
                        }
                    }
                    if (uiState.hasContext) {
                        Spacer(modifier = Modifier.width(6.dp))
                        ReplyCoachChip(
                            label = "Clear",
                            modifier = Modifier.testTag("clear_reply_context")
                        ) {
                            session.clear()
                            feedback = null
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyCoachChip(
    label: String,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    onClick: () -> Unit
) {
    val colors = LocalKeyboardColors.current
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = if (emphasized) colors.accent else colors.chip,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = label,
            color = if (emphasized) colors.onAccent else colors.onChip,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(androidx.compose.ui.graphics.Color.Transparent)
                .padding(horizontal = 9.dp, vertical = 5.dp),
            maxLines = 1
        )
    }
}
