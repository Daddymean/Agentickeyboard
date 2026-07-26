package io.github.daddymean.agentickeyboard.service

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.daddymean.agentickeyboard.AgenticKeyboardApplication
import io.github.daddymean.agentickeyboard.ClipboardHistoryActivity
import io.github.daddymean.agentickeyboard.SnippetVaultActivity
import io.github.daddymean.agentickeyboard.db.ClipboardHistoryItem
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.AgenticKeyboardLayout
import io.github.daddymean.agentickeyboard.ui.ClipboardHistoryBar
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModelFactory
import io.github.daddymean.agentickeyboard.ui.ReplyCompletenessBar
import io.github.daddymean.agentickeyboard.ui.SnippetVaultBar
import io.github.daddymean.agentickeyboard.ui.TrustPrismBanner
import io.github.daddymean.agentickeyboard.util.ClipboardCaptureDecision
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryPolicy
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.ReplyCompletenessSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class AgenticKeyboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private companion object {
        const val CONTEXT_CHARS = 1000
        const val MAX_CURSOR_STEPS = 20
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val replyCompletenessSession = ReplyCompletenessSession()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clipboardHistoryEnabled = MutableStateFlow(false)
    private val clipboardHistoryPaused = MutableStateFlow(false)
    private val clipboardStatus = MutableStateFlow<String?>(null)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var viewModel: KeyboardViewModel
    private lateinit var repository: KeyboardRepository
    private lateinit var settings: KeyboardSettings

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val app = application as AgenticKeyboardApplication
        repository = app.repository
        settings = app.settings
        refreshClipboardSettings()
        viewModel = ViewModelProvider(
            this,
            KeyboardViewModelFactory(repository, settings)
        )[KeyboardViewModel::class.java]
    }

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            val historyEnabled by clipboardHistoryEnabled.collectAsState()
            val historyPaused by clipboardHistoryPaused.collectAsState()
            val historyStatus by clipboardStatus.collectAsState()
            val sensitiveField by viewModel.isSensitiveField.collectAsState()

            Column {
                TrustPrismBanner(viewModel)
                ReplyCompletenessBar(
                    viewModel = viewModel,
                    session = replyCompletenessSession,
                    onSendAnyway = { performEnterAction() }
                )
                SnippetVaultBar(
                    viewModel = viewModel,
                    repository = repository,
                    onReplaceDraft = { replaceDraftBeforeCursor(it) },
                    onOpenManager = { openSnippetVaultManager() }
                )
                ClipboardHistoryBar(
                    repository = repository,
                    enabled = historyEnabled,
                    paused = historyPaused,
                    sensitiveField = sensitiveField,
                    statusMessage = historyStatus,
                    onEnable = { enableClipboardHistory() },
                    onTogglePause = { toggleClipboardHistoryPause() },
                    onCaptureCurrent = { captureCurrentClipboard(silent = false) },
                    onInsert = { insertClipboardItem(it) },
                    onOpenManager = { openClipboardHistoryManager() }
                )
                AgenticKeyboardLayout(
                    viewModel = viewModel,
                    onKeyPress = { text ->
                        currentInputConnection?.commitText(text, 1)
                    },
                    onDelete = {
                        currentInputConnection?.deleteSurroundingText(1, 0)
                    },
                    onAction = { performEnterAction() },
                    onMicPress = { switchToVoiceInput() },
                    onCursorMove = { steps -> moveCursor(steps) },
                    inputConnectionProvider = { currentInputConnection }
                )
            }
        }
        return composeView
    }

    /** Replaces the bounded draft that triggered `/v` or `/find` recall. */
    private fun replaceDraftBeforeCursor(text: String) {
        val ic = currentInputConnection ?: return
        val existing = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.length ?: 0
        ic.beginBatchEdit()
        try {
            if (existing > 0) ic.deleteSurroundingText(existing, 0)
            ic.commitText(text, 1)
        } finally {
            ic.endBatchEdit()
        }
        syncEditorText()
    }

    private fun insertClipboardItem(item: ClipboardHistoryItem) {
        if (viewModel.isSensitiveField.value) return
        currentInputConnection?.commitText(item.content, 1) ?: return
        clipboardStatus.value = "Inserted from local history."
        serviceScope.launch { repository.recordClipboardUse(item.id) }
    }

    /** Opens the local manager only after the user taps Manage in the vault bar. */
    private fun openSnippetVaultManager() {
        val intent = Intent(this, SnippetVaultActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun openClipboardHistoryManager() {
        val intent = Intent(this, ClipboardHistoryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun enableClipboardHistory() {
        settings.isClipboardHistoryEnabled = true
        settings.isClipboardHistoryPaused = false
        refreshClipboardSettings()
        clipboardStatus.value = "Enabled. Capturing the current clipboard locally."
        captureCurrentClipboard(silent = false)
    }

    private fun toggleClipboardHistoryPause() {
        if (!settings.isClipboardHistoryEnabled) return
        settings.isClipboardHistoryPaused = !settings.isClipboardHistoryPaused
        refreshClipboardSettings()
        clipboardStatus.value = if (settings.isClipboardHistoryPaused) {
            "Paused. Existing clips remain available."
        } else {
            "Resumed. Foreground capture is active."
        }
        if (!settings.isClipboardHistoryPaused) captureCurrentClipboard(silent = true)
    }

    private fun refreshClipboardSettings() {
        clipboardHistoryEnabled.value = settings.isClipboardHistoryEnabled
        clipboardHistoryPaused.value = settings.isClipboardHistoryPaused
    }

    /**
     * Reads only the current primary text clip, only while the opted-in IME is in
     * the foreground. There is deliberately no ClipboardManager listener.
     */
    private fun captureCurrentClipboard(silent: Boolean) {
        refreshClipboardSettings()
        if (!settings.isClipboardHistoryEnabled || settings.isClipboardHistoryPaused) return
        if (viewModel.isSensitiveField.value) return

        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = manager.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.text
            ?.toString()
            ?: run {
                if (!silent) clipboardStatus.value = "Clipboard has no plain text to retain."
                return
            }

        when (val decision = ClipboardHistoryPolicy.evaluate(text)) {
            is ClipboardCaptureDecision.Accept -> {
                serviceScope.launch {
                    repository.captureClipboard(
                        content = decision.content,
                        contentHash = decision.contentHash,
                        retentionDays = settings.clipboardRetentionDays
                    )
                }
                if (!silent) clipboardStatus.value = "Saved locally. Duplicate clips collapse automatically."
            }
            is ClipboardCaptureDecision.Reject -> {
                if (!silent) {
                    clipboardStatus.value = ClipboardHistoryPolicy.rejectionMessage(decision.reason)
                }
            }
        }
    }

    /**
     * Triggers the editor-defined IME action (Send, Search, Done, ...) when one
     * exists, otherwise synthesizes a full Enter key press/release pair.
     */
    private fun performEnterAction() {
        val ic = currentInputConnection ?: return
        val options = currentInputEditorInfo?.imeOptions ?: EditorInfo.IME_NULL
        val action = options and EditorInfo.IME_MASK_ACTION
        val hasAction = action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
        if (hasAction && action == EditorInfo.IME_ACTION_SEND) {
            val draft = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: ""
            if (replyCompletenessSession.interceptSend(draft, viewModel.isSensitiveField.value)) return
            if (viewModel.interceptSend(draft)) return
        }
        if (hasAction) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    /** Moves the cursor left (negative) or right (positive) via DPAD key events. */
    private fun moveCursor(steps: Int) {
        if (steps == 0) return
        val ic = currentInputConnection ?: return
        val keyCode = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(minOf(kotlin.math.abs(steps), MAX_CURSOR_STEPS)) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }
    }

    /**
     * Hands input off to an installed voice IME when one is enabled; otherwise
     * shows the system input-method picker so the user can choose.
     */
    private fun switchToVoiceInput() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val voiceIme = imm.enabledInputMethodList.firstOrNull { imi ->
            (0 until imi.subtypeCount).any { imi.getSubtypeAt(it).mode == "voice" }
        }
        try {
            if (voiceIme != null) {
                switchInputMethod(voiceIme.id)
            } else {
                imm.showInputMethodPicker()
            }
        } catch (e: Exception) {
            imm.showInputMethodPicker()
        }
    }

    private fun syncEditorText() {
        val textBefore = currentInputConnection?.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: ""
        viewModel.setInputText(textBefore)
        val selected = currentInputConnection?.getSelectedText(0)?.toString()
        viewModel.setSelectionActive(!selected.isNullOrBlank())
    }

    /** Friendly app name for [packageName], or "" if it can't be resolved. */
    @Suppress("DEPRECATION")
    private fun resolveAppLabel(packageName: String?): String {
        if (packageName.isNullOrBlank()) return ""
        return runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(packageName, 0)
            ).toString()
        }.getOrDefault("")
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        replyCompletenessSession.clear()
        clipboardStatus.value = null

        viewModel.onEditorStarted(info?.packageName, resolveAppLabel(info?.packageName), info?.inputType ?: 0)
        refreshClipboardSettings()

        if (!restarting) {
            viewModel.dismissResults()
        }
        syncEditorText()
        captureCurrentClipboard(silent = true)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        syncEditorText()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        refreshClipboardSettings()
        captureCurrentClipboard(silent = true)
    }

    override fun onWindowHidden() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        replyCompletenessSession.clear()
        clipboardStatus.value = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onFinishInput()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        store.clear()
        super.onDestroy()
    }
}
