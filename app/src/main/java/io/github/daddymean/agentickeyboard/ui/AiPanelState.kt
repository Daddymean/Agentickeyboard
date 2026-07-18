package io.github.daddymean.agentickeyboard.ui

import io.github.daddymean.agentickeyboard.network.GrammarCorrectionResponse
import io.github.daddymean.agentickeyboard.network.ToneAnalysisResponse

/**
 * The single active AI surface shown by the keyboard.
 *
 * Only one state can be active at a time, preventing stale results from
 * coexisting and relying on UI priority order to decide which one wins.
 */
sealed interface AiPanelState {
    object Idle : AiPanelState
    object Loading : AiPanelState

    data class ReplyIntent(val contextMessage: String) : AiPanelState
    data class Replies(val suggestions: List<String>) : AiPanelState
    data class Grammar(val result: GrammarCorrectionResponse) : AiPanelState
    data class Tone(val result: ToneAnalysisResponse) : AiPanelState
    data class Summary(val text: String, val original: String) : AiPanelState
    data class Translation(val text: String, val original: String) : AiPanelState
    data class Rewrite(val text: String, val original: String, val styleLabel: String) : AiPanelState
    data class Compose(val text: String) : AiPanelState
    data class Explanation(val text: String) : AiPanelState
    data class Continuation(val text: String) : AiPanelState

    /** Text accepted by the result-refinement chips, matching prior behavior. */
    val refinableText: String?
        get() = when (this) {
            is Grammar -> result.corrected
            is Summary -> text
            is Translation -> text
            is Rewrite -> text
            is Compose -> text
            is Continuation -> text
            else -> null
        }

    /** Original text shown in expanded before/after comparisons. */
    val sourceText: String?
        get() = when (this) {
            is Grammar -> result.original
            is Summary -> original
            is Translation -> original
            is Rewrite -> original
            else -> null
        }

    /** True for a completed result that supports regenerate/dismiss controls. */
    val hasResult: Boolean
        get() = when (this) {
            Idle, Loading, is ReplyIntent -> false
            else -> true
        }
}
