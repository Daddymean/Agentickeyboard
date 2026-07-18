package io.github.daddymean.agentickeyboard.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Owns the lifecycle of the single foreground AI session.
 *
 * Action-specific prompt decisions remain in [KeyboardViewModel]. This class
 * centralizes cancellation, loading cleanup, panel publication, dismissal, and
 * regenerate bookkeeping so those mechanics cannot drift between actions.
 */
internal class AiSessionController(
    private val scope: CoroutineScope
) {
    private val _panelState = MutableStateFlow<AiPanelState>(AiPanelState.Idle)
    val panelState: StateFlow<AiPanelState> = _panelState.asStateFlow()

    val currentState: AiPanelState
        get() = _panelState.value

    private var activeJob: Job? = null
    private var regenerateAction: (() -> Unit)? = null

    fun setRegenerateAction(action: (() -> Unit)?) {
        regenerateAction = action
    }

    fun regenerate() {
        regenerateAction?.invoke()
    }

    fun publish(state: AiPanelState) {
        _panelState.value = state
    }

    fun clear() {
        _panelState.value = AiPanelState.Idle
    }

    /**
     * Starts a foreground AI action after cancelling the previous one. If the
     * action exits or fails without publishing a result, the loading state is
     * cleared automatically.
     */
    fun launch(block: suspend AiSessionController.() -> Unit) {
        val previous = activeJob
        activeJob = scope.launch {
            previous?.cancelAndJoin()
            publish(AiPanelState.Loading)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Individual actions publish user-facing fallbacks. This final
                // guard keeps an unexpected failure from crashing the IME.
            } finally {
                if (currentState == AiPanelState.Loading) {
                    clear()
                }
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        if (currentState == AiPanelState.Loading) {
            clear()
        }
    }
}
