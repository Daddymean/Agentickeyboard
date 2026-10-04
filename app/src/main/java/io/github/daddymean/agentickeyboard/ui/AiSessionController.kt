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

    /**
     * Transitional write bridge for [KeyboardViewModel]'s action-specific result
     * publishers. The controller remains the sole owner of the backing state;
     * individual actions can migrate to [publish] incrementally.
     */
    internal val mutablePanelState: MutableStateFlow<AiPanelState>
        get() = _panelState

    val currentState: AiPanelState
        get() = _panelState.value

    private var activeJob: Job? = null
    private var regenerateAction: (() -> Unit)? = null

    /**
     * The editor text the current result was generated from, captured when the
     * action launched. Apply compares it with the live draft so a result can
     * never overwrite text typed or selected after the request (see AiApplyGuard).
     * Null when no result is bound to a draft.
     */
    var resultSource: String? = null
        private set

    /** Binds the shown result to [source] (e.g. a surfaced background proofread). */
    fun bindResultSource(source: String?) {
        resultSource = source
    }

    fun setRegenerateAction(action: (() -> Unit)?) {
        regenerateAction = action
    }

    fun regenerate() {
        regenerateAction?.invoke()
    }

    fun publish(state: AiPanelState) {
        _panelState.value = state
    }

    /**
     * Dismisses the panel and invalidates the session: the in-flight action is
     * cancelled so a late response cannot republish into the panel after the
     * user dismissed it or moved to another editor.
     */
    fun clear() {
        activeJob?.cancel()
        activeJob = null
        regenerateAction = null
        resultSource = null
        _panelState.value = AiPanelState.Idle
    }

    /**
     * Starts a foreground AI action after cancelling the previous one. If the
     * action exits or fails without publishing a result, the loading state is
     * cleared automatically.
     */
    fun launch(source: String? = null, block: suspend AiSessionController.() -> Unit) {
        val previous = activeJob
        resultSource = source
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
                    _panelState.value = AiPanelState.Idle
                }
            }
        }
    }

    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        if (currentState == AiPanelState.Loading) {
            _panelState.value = AiPanelState.Idle
        }
    }
}
