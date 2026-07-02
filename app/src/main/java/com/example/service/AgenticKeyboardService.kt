package com.example.service

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
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
import com.example.AgenticKeyboardApplication
import com.example.ui.AgenticKeyboardLayout
import com.example.ui.KeyboardViewModel
import com.example.ui.KeyboardViewModelFactory

class AgenticKeyboardService : InputMethodService(), LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private companion object {
        const val CONTEXT_CHARS = 1000
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

        // Instantiate ViewModel with repo from Application singleton
        val app = application as AgenticKeyboardApplication
        viewModel = ViewModelProvider(this, KeyboardViewModelFactory(app.repository))[KeyboardViewModel::class.java]
    }

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this)
        composeView.setViewTreeLifecycleOwner(this)
        composeView.setViewTreeViewModelStoreOwner(this)
        composeView.setViewTreeSavedStateRegistryOwner(this)

        composeView.setContent {
            AgenticKeyboardLayout(
                viewModel = viewModel,
                onKeyPress = { text ->
                    currentInputConnection?.commitText(text, 1)
                },
                onDelete = {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                },
                onAction = { performEnterAction() },
                // Resolved lazily on every use: the active InputConnection changes
                // whenever the user switches editors, so it must never be captured.
                inputConnectionProvider = { currentInputConnection }
            )
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
        if (hasAction) {
            ic.performEditorAction(action)
        } else {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    private fun syncEditorText() {
        val textBefore = currentInputConnection?.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString() ?: ""
        viewModel.setInputText(textBefore)
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)

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
