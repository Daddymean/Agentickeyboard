package io.github.daddymean.agentickeyboard.service

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Column
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
import io.github.daddymean.agentickeyboard.ui.AgenticKeyboardLayout
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel
import io.github.daddymean.agentickeyboard.ui.KeyboardViewModelFactory
import io.github.daddymean.agentickeyboard.ui.TrustPrismBanner

class AgenticKeyboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private companion object {
        const val CONTEXT_CHARS = 1000
        const val MAX_CURSOR_STEPS = 20
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private lateinit var viewModel: KeyboardViewModel

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        // Instantiate ViewModel with repo + settings from Application singleton
        val app = application as AgenticKeyboardApplication
        viewModel = ViewModelProvider(
            this,
            KeyboardViewModelFactory(app.repository, app.settings)
        )[KeyboardViewModel::class.java]
    }

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            Column {
                TrustPrismBanner(viewModel)
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
                    // Resolved lazily on every use: the active InputConnection changes
                    // whenever the user switches editors, so it must never be captured.
                    inputConnectionProvider = { currentInputConnection }
                )
            }
        }
        return composeView
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
        // Opt-in send-guard: in messaging (Send) contexts, hold a hostile-reading
        // draft back once so the shelf can ask "Send anyway?"; the next Enter sends.
        if (hasAction && action == EditorInfo.IME_ACTION_SEND) {
            val draft = ic.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: ""
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
        // Same non-blank rule as the layout's selectedText(): AI actions treat a
        // whitespace-only selection as no selection.
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

        // Detect password/secure fields (suppresses AI + learning) and restore the
        // persona last used in this app. The IME has package visibility to the app
        // it serves, so it can resolve a friendly label to store alongside.
        viewModel.onEditorStarted(info?.packageName, resolveAppLabel(info?.packageName), info?.inputType ?: 0)

        // Fetch active editor contents to initialize suggestions/state, and drop
        // any AI result that referred to the previous editor.
        if (!restarting) {
            viewModel.dismissResults()
        }
        syncEditorText()
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
        // Keeps ViewModel state (and therefore the suggestion shelf) in sync with
        // whatever the user types or where they move the cursor.
        syncEditorText()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onWindowHidden() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onWindowHidden()
    }

    override fun onFinishInput() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onFinishInput()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        super.onDestroy()
    }
}
