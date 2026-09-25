package io.github.daddymean.agentickeyboard.ui

import android.view.inputmethod.InputConnection
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.daddymean.agentickeyboard.db.ClipboardHistoryItem
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.util.ReplyCompletenessSession

/** The complete service view tree, shared with window-size regression tests. */
@Composable
fun KeyboardImeContent(
    viewModel: KeyboardViewModel,
    repository: KeyboardRepository,
    replyCompletenessSession: ReplyCompletenessSession,
    historyEnabled: Boolean,
    historyPaused: Boolean,
    historyStatus: String?,
    sensitiveField: Boolean,
    onSendAnyway: () -> Unit = {},
    onReplaceDraft: (String, Int?) -> Unit = { _, _ -> },
    onOpenSnippetManager: () -> Unit = {},
    onTogglePause: () -> Unit = {},
    onCaptureCurrent: () -> Unit = {},
    onInsertClipboard: (ClipboardHistoryItem) -> Unit = {},
    onOpenClipboardManager: () -> Unit = {},
    onKeyPress: (String) -> Unit = {},
    onDelete: () -> Unit = {},
    onAction: () -> Unit = {},
    onMicPress: () -> Unit = {},
    onCursorMove: (Int) -> Unit = {},
    inputConnectionProvider: () -> InputConnection? = { null }
) {
    ProvideKeyboardMetrics {
        Column(Modifier.testTag("complete_ime")) {
            TrustPrismBanner(viewModel)
            ReplyCompletenessBar(viewModel, replyCompletenessSession, onSendAnyway)
            SnippetVaultBar(viewModel, repository, onReplaceDraft, onOpenSnippetManager)
            ClipboardHistoryBar(
                repository, historyEnabled, historyPaused, sensitiveField, historyStatus,
                onTogglePause, onCaptureCurrent, onInsertClipboard, onOpenClipboardManager
            )
            AgenticKeyboardLayout(
                viewModel = viewModel,
                onKeyPress = onKeyPress,
                onDelete = onDelete,
                onAction = onAction,
                onMicPress = onMicPress,
                onCursorMove = onCursorMove,
                inputConnectionProvider = inputConnectionProvider
            )
        }
    }
}
