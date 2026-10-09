package io.github.daddymean.agentickeyboard.service

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import dev.context.core.client.ContextClient
import io.github.daddymean.agentickeyboard.AgenticKeyboardApplication
import io.github.daddymean.agentickeyboard.ClipboardHistoryActivity
import io.github.daddymean.agentickeyboard.ConversationCaptureActivity
import io.github.daddymean.agentickeyboard.MainActivity
import io.github.daddymean.agentickeyboard.SnippetVaultActivity
import io.github.daddymean.agentickeyboard.db.ClipboardHistoryItem
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.ui.AgenticKeyboardLayout
import io.github.daddymean.agentickeyboard.ui.ConversationContextPreview
import io.github.daddymean.agentickeyboard.ui.ConversationContextResult
import io.github.daddymean.agentickeyboard.ui.AiPanelState
import io.github.daddymean.agentickeyboard.ui.ConversationContextBar
import io.github.daddymean.agentickeyboard.ui.ConversationContextUiState
import io.github.daddymean.agentickeyboard.ui.ConversationContextAction
import io.github.daddymean.agentickeyboard.ui.ClipboardHistoryBar
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModelFactory
import io.github.daddymean.agentickeyboard.ui.ProvideKeyboardMetrics
import io.github.daddymean.agentickeyboard.ui.ReplyCompletenessBar
import io.github.daddymean.agentickeyboard.ui.SnippetVaultBar
import io.github.daddymean.agentickeyboard.ui.TrustPrismBanner
import io.github.daddymean.agentickeyboard.util.ClipboardCaptureDecision
import io.github.daddymean.agentickeyboard.util.commitTextWithCaret
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryPolicy
import io.github.daddymean.agentickeyboard.util.ClipboardSensitivity
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.EditClipboardAction
import io.github.daddymean.agentickeyboard.util.ReplyCompletenessSession
import io.github.daddymean.agentickeyboard.util.ConversationCapturePreferences
import io.github.daddymean.agentickeyboard.util.VisibleContextLease
import io.github.daddymean.agentickeyboard.util.VisibleContextPolicy
import io.github.daddymean.agentickeyboard.util.SelectionCommand
import io.github.daddymean.agentickeyboard.util.SelectionPlanner
import io.github.daddymean.agentickeyboard.util.SelectionRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class AgenticKeyboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private companion object {
        const val TAG = "AgenticKeyboardIME"
        const val CONTEXT_CHARS = 1000
        const val MAX_CURSOR_STEPS = 20
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private val replyCompletenessSession = ReplyCompletenessSession()
    private val conversationContext = MutableStateFlow(ConversationContextUiState())
    private val contextLease = VisibleContextLease()
    private val contextToolActive = MutableStateFlow(false)
    private var contextExpiryJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clipboardHistoryEnabled = MutableStateFlow(false)
    private val clipboardHistoryPaused = MutableStateFlow(false)
    private val clipboardStatus = MutableStateFlow<String?>(null)
    private val navigationBarInsetPx = MutableStateFlow(0)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var viewModel: KeyboardViewModel
    private lateinit var repository: KeyboardRepository
    private lateinit var settings: KeyboardSettings

    // One client per IME process. Binding is lazy and drops when idle, and every
    // call degrades silently when the context service is not installed.
    private lateinit var contextClient: ContextClient

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        val app = application as AgenticKeyboardApplication
        repository = app.repository
        settings = app.settings
        contextClient = ContextClient(this)
        refreshClipboardSettings()
        viewModel = ViewModelProvider(
            this,
            KeyboardViewModelFactory(repository, settings, contextClient)
        )[KeyboardViewModel::class.java]
        serviceScope.launch(Dispatchers.Main) {
            ConversationCaptureService.invalidations.collect {
                if (conversationContext.value.active || conversationContext.value.pending != null) {
                    clearConversationContext("Screen changed. Capture the conversation again.")
                }
            }
        }
        viewModel.setReplyContextValidator {
            conversationContext.value.active && validateConversationContext()
        }
    }

    override fun onCreateInputView(): View {
        // Compose builds its recomposer from the root of the window, not from the
        // ComposeView, so the owners must be on the IME window's decor view too —
        // otherwise the first time the keyboard is shown it throws
        // "ViewTreeLifecycleOwner not found" and the IME process dies.
        window?.window?.decorView?.let { decor ->
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeViewModelStoreOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
        }
        val composeView = ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)
        // With edge-to-edge (targetSdk 35+) the IME window is laid out behind the
        // navigation bar, so the bottom key row would sit under the system's
        // back/home/gesture area. Track the bar's height and lift the keys by it.
        ViewCompat.setOnApplyWindowInsetsListener(composeView) { _, insets ->
            navigationBarInsetPx.value =
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            insets
        }

        composeView.setContent {
            val historyEnabled by clipboardHistoryEnabled.collectAsState()
            val historyPaused by clipboardHistoryPaused.collectAsState()
            val historyStatus by clipboardStatus.collectAsState()
            val sensitiveField by viewModel.isSensitiveField.collectAsState()
            val aiToolsExpanded by viewModel.aiToolsExpanded.collectAsState()
            val keyHeightScale by viewModel.keyHeightScale.collectAsState()
            val navInsetPx by navigationBarInsetPx.collectAsState()
            val capturedContext by conversationContext.collectAsState()
            val themeOverride by viewModel.themeOverride.collectAsState()
            val offline by viewModel.isOfflineMode.collectAsState()
            val contextResultActive by contextToolActive.collectAsState()
            val contextPanel by viewModel.aiPanelState.collectAsState()
            val navigationBarInset = with(LocalDensity.current) { navInsetPx.toDp() }

            // Every surface below sizes itself from the window the IME was given,
            // so the keyboard still leaves room for the field in short windows.
            ProvideKeyboardMetrics(keyHeightScale) {
                Column {
                    TrustPrismBanner(viewModel)
                    if (aiToolsExpanded && !sensitiveField) {
                        ConversationContextBar(
                            state = capturedContext,
                            themeOverride = themeOverride,
                            offline = offline,
                            onCapture = { captureConversation() },
                            onSettings = { openConversationCaptureSettings() },
                            onClear = { clearConversationContext() },
                            onAction = { runConversationAction(it) }
                        )
                    }
                    ReplyCompletenessBar(
                        viewModel = viewModel,
                        session = replyCompletenessSession,
                        onSendAnyway = { performEnterAction() },
                        showIdle = aiToolsExpanded,
                        onClipboardContext = { clearConversationContext() },
                        onClearContext = { clearConversationContext() }
                    )
                    SnippetVaultBar(
                        viewModel = viewModel,
                        repository = repository,
                        onReplaceDraft = { text, caret -> replaceDraftBeforeCursor(text, caret) },
                        onOpenManager = { openSnippetVaultManager() }
                    )
                    ClipboardHistoryBar(
                        repository = repository,
                        enabled = historyEnabled,
                        paused = historyPaused,
                        sensitiveField = sensitiveField,
                        statusMessage = historyStatus,
                        onTogglePause = { toggleClipboardHistoryPause() },
                        onCaptureCurrent = { captureCurrentClipboard(silent = false) },
                        onInsert = { insertClipboardItem(it) },
                        onOpenManager = { openClipboardHistoryManager() }
                    )
                    Box {
                        Box(modifier = if (capturedContext.pending != null || contextResultActive) {
                            Modifier.clearAndSetSemantics { }
                        } else Modifier) {
                            AgenticKeyboardLayout(
                                viewModel = viewModel,
                                suppressAiPanels = contextResultActive,
                                onKeyPress = { text ->
                                    currentInputConnection?.commitText(text, 1)
                                },
                                onDelete = {
                                    currentInputConnection?.deleteSurroundingText(1, 0)
                                },
                                onAction = { performEnterAction() },
                                onMicPress = { switchToVoiceInput() },
                                onCursorMove = { steps -> moveCursor(steps) },
                                onSelectionCommand = { command -> applySelectionCommand(command) },
                                onClipboardAction = { action -> performClipboardAction(action) },
                                inputConnectionProvider = { currentInputConnection },
                                navigationBarInset = navigationBarInset,
                                onOpenSettings = { openKeyboardSettings() }
                            )
                        }
                        capturedContext.pending?.takeIf { !sensitiveField }?.let { pending ->
                            Box(Modifier.matchParentSize()) {
                                ConversationContextPreview(
                                    pending = pending,
                                    themeOverride = themeOverride,
                                    onConfirm = { confirmConversationContext(it) },
                                    onClear = { clearConversationContext() }
                                )
                            }
                        }
                        if (contextResultActive && !sensitiveField) {
                            Box(Modifier.matchParentSize()) {
                                ConversationContextResult(
                                    panel = contextPanel,
                                    themeOverride = themeOverride,
                                    onIntent = { viewModel.chooseReplyIntent(it) },
                                    onInsert = { text ->
                                        if (validateConversationContext()) {
                                            currentInputConnection?.commitText(text, 1)
                                            clearConversationContext()
                                            syncEditorText()
                                        }
                                    },
                                    onDismiss = {
                                        viewModel.dismissResults()
                                        contextToolActive.value = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        return composeView
    }

    private fun replaceDraftBeforeCursor(text: String, cursorOffset: Int? = null) {
        val ic = currentInputConnection ?: return
        val existing = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.length ?: 0
        ic.beginBatchEdit()
        try {
            if (existing > 0) ic.deleteSurroundingText(existing, 0)
            ic.commitTextWithCaret(text, cursorOffset)
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

    private fun openKeyboardSettings() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)
        requestHideSelf(0)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Unable to open keyboard settings", it) }
    }

    private fun openConversationCaptureSettings() {
        val target = currentInputEditorInfo?.packageName ?: return
        val intent = Intent(this, ConversationCaptureActivity::class.java)
            .putExtra(ConversationCaptureActivity.EXTRA_SOURCE_PACKAGE, target)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        clearConversationContext()
        requestHideSelf(0)
        runCatching { startActivity(intent) }
    }

    private fun clearConversationContext(status: String? = null) {
        // Clearing an attached conversation must cancel only an AI action that
        // actually uses that conversation. A regular grammar/rewrite/compose
        // result may coexist with an attached context and should survive expiry,
        // accessibility invalidation, or the user's Clear action.
        val hadContextTool = contextToolActive.value
        contextExpiryJob?.cancel()
        contextExpiryJob = null
        contextLease.clear()
        contextToolActive.value = false
        conversationContext.value = ConversationContextUiState(status = status)
        replyCompletenessSession.clear()
        if (hadContextTool) viewModel.dismissResults()
    }

    private fun captureConversation() {
        clearConversationContext()
        if (viewModel.isSensitiveField.value) return
        if (viewModel.aiPanelState.value != AiPanelState.Idle) {
            conversationContext.value = ConversationContextUiState(
                status = "Dismiss the current AI panel before capturing.")
            return
        }
        val target = currentInputEditorInfo?.packageName ?: return
        if (!ConversationCapturePreferences(this).isAllowed(target) ||
            !ConversationCaptureService.isConnected()) {
            openConversationCaptureSettings()
            return
        }
        val snapshot = ConversationCaptureService.capture(target)
        if (snapshot == null) {
            conversationContext.value = ConversationContextUiState(
                status = "Visible text is unavailable. Use the clipboard fallback.")
            return
        }
        contextLease.capture(snapshot, SystemClock.elapsedRealtime())
        conversationContext.value = ConversationContextUiState(pending = snapshot)
        contextExpiryJob = serviceScope.launch(Dispatchers.Main) {
            delay(VisibleContextPolicy.TTL_MS)
            clearConversationContext("Context expired. Capture again to use it.")
        }
    }

    private fun validateConversationContext(): Boolean {
        val target = currentInputEditorInfo?.packageName
        val current = if (!viewModel.isSensitiveField.value && target != null) {
            ConversationCaptureService.capture(target)
        } else null
        if (!contextLease.matches(current, SystemClock.elapsedRealtime())) {
            clearConversationContext("Context changed or expired. Capture again.")
            return false
        }
        return true
    }

    private fun confirmConversationContext(indices: List<Int>) {
        val snapshot = conversationContext.value.pending ?: return
        if (!validateConversationContext()) return
        val text = indices.distinct().sorted().mapNotNull { snapshot.lines.getOrNull(it) }.joinToString("\n")
        if (!replyCompletenessSession.setIncomingContext(text)) return
        conversationContext.value = ConversationContextUiState(active = true)
    }

    private fun runConversationAction(action: ConversationContextAction) {
        if (!conversationContext.value.active || !validateConversationContext()) return
        val text = replyCompletenessSession.incomingContext() ?: return
        contextToolActive.value = true
        when (action) {
            ConversationContextAction.REPLY -> viewModel.requestReplyIdeas(text, ephemeralContext = true)
            ConversationContextAction.SUMMARY -> viewModel.summarizeMessage(text, ephemeralContext = true)
            ConversationContextAction.TRANSLATE -> viewModel.translateText(text, ephemeralContext = true)
            ConversationContextAction.EXPLAIN -> viewModel.explainText(text, ephemeralContext = true)
        }
    }

    private fun openSnippetVaultManager() {
        val intent = Intent(this, SnippetVaultActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Unable to open Snippet Vault manager", it) }
    }

    private fun openClipboardHistoryManager() {
        val intent = Intent(this, ClipboardHistoryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
            .onFailure { Log.w(TAG, "Unable to open clipboard manager", it) }
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
     * the foreground. Android clipboard access can still be denied by an OEM,
     * device policy, or a lifecycle race; those failures must never terminate the
     * keyboard process.
     */
    private fun captureCurrentClipboard(silent: Boolean) {
        refreshClipboardSettings()
        if (!settings.isClipboardHistoryEnabled || settings.isClipboardHistoryPaused) return
        if (viewModel.isSensitiveField.value) return

        // One read: text and sensitivity flag come from the same ClipData.
        val snapshot = runCatching {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            ClipboardSensitivity.readPrimaryClip(manager)
        }.onFailure {
            Log.w(TAG, "Clipboard access unavailable; continuing without capture", it)
        }.getOrNull()
        val text = snapshot?.text
        val flaggedSensitive = snapshot?.flaggedSensitive ?: false

        if (text == null) {
            if (!silent) clipboardStatus.value = "Clipboard is unavailable or has no plain text."
            return
        }

        when (val decision = ClipboardHistoryPolicy.evaluate(text, flaggedSensitive)) {
            is ClipboardCaptureDecision.Accept -> {
                serviceScope.launch {
                    runCatching {
                        repository.captureClipboard(
                            content = decision.content,
                            contentHash = decision.contentHash,
                            retentionDays = settings.clipboardRetentionDays
                        )
                    }.onFailure {
                        Log.e(TAG, "Clipboard history write failed", it)
                    }
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

    private fun performEnterAction() {
        val ic = currentInputConnection ?: return
        val options = currentInputEditorInfo?.imeOptions ?: EditorInfo.IME_NULL
        val action = options and EditorInfo.IME_MASK_ACTION
        val hasAction = action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED &&
            (options and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
        if (hasAction && action == EditorInfo.IME_ACTION_SEND) {
            if (conversationContext.value.active) validateConversationContext()
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
     * Runs one edit-bar command against the focused field.
     *
     * Cursor movement via DPAD events (see [moveCursor]) cannot express a
     * selection, so the edit bar goes through the extracted text instead: it
     * reads the real offsets, asks [SelectionPlanner] where the selection
     * belongs, and sets it in one call. Fields that refuse to extract their
     * text — some password and WebView editors do — report no offsets, and the
     * command is dropped rather than guessed at.
     */
    private fun applySelectionCommand(command: SelectionCommand) {
        val ic = currentInputConnection ?: return

        // Select-all goes to the editor rather than the planner. An extracted
        // snapshot can cover only part of the document, so planning it here
        // would select the snapshot and quietly leave the rest unselected.
        if (command == SelectionCommand.SelectAll) {
            ic.performContextMenuAction(android.R.id.selectAll)
            syncEditorText()
            return
        }

        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return
        val text = extracted.text?.toString() ?: return
        val start = extracted.selectionStart
        val end = extracted.selectionEnd
        if (start < 0 || end < 0) return

        val next = SelectionPlanner.plan(text, SelectionRange(start, end), command)

        // Snapshot-relative -> absolute, via the pure helper so the mapping is
        // covered by a test rather than only by this comment.
        val absolute = SelectionPlanner.toDocumentRange(next, extracted.startOffset)

        // No explicit state push here: setSelection makes the host call
        // onUpdateSelection, which runs syncEditorText and keeps one definition
        // of "has a selection" for the whole keyboard.
        ic.setSelection(absolute.start, absolute.end)
    }

    /**
     * Cut / copy / paste handed to the host editor, which owns the real
     * clipboard interaction and its own permission prompts.
     */
    private fun performClipboardAction(action: EditClipboardAction) {
        val ic = currentInputConnection ?: return
        val id = when (action) {
            EditClipboardAction.Cut -> android.R.id.cut
            EditClipboardAction.Copy -> android.R.id.copy
            EditClipboardAction.Paste -> android.R.id.paste
        }
        ic.performContextMenuAction(id)
        syncEditorText()
    }

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
        // Two different questions: whether an AI action has a meaningful target
        // (non-blank), and whether the editor holds any selection at all, which
        // a run of spaces or newlines satisfies and the edit bar acts on.
        viewModel.setSelectionActive(!selected.isNullOrBlank())
        viewModel.setSelectionRangeActive(!selected.isNullOrEmpty())
    }

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
        clearConversationContext()
        clipboardStatus.value = null

        viewModel.onEditorStarted(
            info?.packageName,
            resolveAppLabel(info?.packageName),
            info?.inputType ?: 0,
            info?.imeOptions ?: 0
        )
        refreshClipboardSettings()

        if (!restarting) {
            viewModel.dismissResults()
            viewModel.onNewInputSession()
            // The edit bar is opt-in per input session, so a genuinely new field
            // starts with it closed. This is the same boundary dismissResults
            // uses: a restart of the same editor (an input-view rebuild) keeps
            // the bar open, a different field does not inherit it.
            viewModel.setEditBarExpanded(false)
        }
        syncEditorText()
        // Do not read the clipboard here. On Android 13+ and some OEM builds,
        // onStartInput can run before the IME window is recognized as foreground.
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
        // Refresh the context chip off the keystroke path: returns the cache at
        // once and fetches in the background only when it is stale, so the
        // context service is not kept bound by every keyboard show.
        if (!viewModel.isSensitiveField.value) contextClient.cachedSnapshot()
    }

    override fun onWindowHidden() {
        clearConversationContext()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        // The field is gone: cancel any in-flight AI action so its result cannot
        // appear later in a different editor.
        viewModel.dismissResults()
        clearConversationContext()
        clipboardStatus.value = null
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onFinishInput()
    }

    override fun onDestroy() {
        clearConversationContext()
        viewModel.setReplyContextValidator(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        serviceScope.cancel()
        store.clear()
        // Flushes queued notes (bounded) and unbinds in the background.
        if (::contextClient.isInitialized) contextClient.close()
        super.onDestroy()
    }
}
