package com.example.service

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent
import android.view.View
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
                onAction = {
                    currentInputConnection?.sendKeyEvent(
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                    )
                },
                currentInputConnection = currentInputConnection
            )
        }
        return composeView
    }

    override fun onStartInput(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        
        // Fetch active editor contents to initialize suggestions/state
        val textBefore = currentInputConnection?.getTextBeforeCursor(200, 0)?.toString() ?: ""
        viewModel.setInputText(textBefore)
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
