package io.github.daddymean.agentickeyboard.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-visible state for the active reply-completeness session.
 *
 * The full incoming message and draft are deliberately absent. The preview is
 * bounded, exists only so the user can verify the explicitly selected context,
 * and is never persisted by this class.
 */
data class ReplyCompletenessUiState(
    val contextPreview: String? = null,
    val contextWasTruncated: Boolean = false,
    val warning: ReplyCompletenessAssessment? = null
) {
    val hasContext: Boolean
        get() = contextPreview != null
}

/**
 * In-memory coordinator between explicit reply-context capture and the Send
 * action. It owns no Android APIs, storage, network access, or background work.
 */
class ReplyCompletenessSession {

    companion object {
        const val MAX_CONTEXT_CHARS = 8_000
        const val MAX_PREVIEW_CHARS = 72
    }

    private var incomingMessage: String? = null
    private var armedSignature: Int? = null
    private var dismissedSignature: Int? = null
    private var bypassNextSend = false

    private val _state = MutableStateFlow(ReplyCompletenessUiState())
    val state = _state.asStateFlow()

    /**
     * Stores context only after an explicit user action. Returns false for an
     * empty clipboard and resets any warning or dismissal from older context.
     */
    fun setIncomingContext(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return false

        val bounded = trimmed.take(MAX_CONTEXT_CHARS)
        incomingMessage = bounded
        armedSignature = null
        dismissedSignature = null
        bypassNextSend = false
        _state.value = ReplyCompletenessUiState(
            contextPreview = previewOf(bounded),
            contextWasTruncated = trimmed.length > bounded.length
        )
        return true
    }

    /** Clears the selected context and all transient warning state. */
    fun clear() {
        incomingMessage = null
        armedSignature = null
        dismissedSignature = null
        bypassNextSend = false
        _state.value = ReplyCompletenessUiState()
    }

    /**
     * Returns true when Send should pause for an advisory warning.
     *
     * A second Send on the same armed draft proceeds, matching Send Guard's
     * existing reversible behavior. Dismissing suppresses only the identical
     * context/draft pair; editing the draft causes a fresh assessment.
     */
    fun interceptSend(draft: String, isSensitiveField: Boolean): Boolean {
        if (isSensitiveField) {
            clear()
            return false
        }

        val incoming = incomingMessage ?: return false
        if (draft.isBlank()) return false

        val signature = signatureOf(incoming, draft)

        if (bypassNextSend) {
            bypassNextSend = false
            clearWarningKeepingContext()
            return false
        }

        if (_state.value.warning != null && armedSignature == signature) {
            clearWarningKeepingContext()
            return false
        }

        if (dismissedSignature == signature) return false

        val assessment = ReplyCompletenessCoach.assess(incoming, draft)
        if (assessment?.shouldWarn != true) {
            clearWarningKeepingContext()
            return false
        }

        armedSignature = signature
        _state.value = _state.value.copy(warning = assessment)
        return true
    }

    /** Clears an armed warning as soon as the user changes the draft. */
    fun onDraftChanged(draft: String) {
        val incoming = incomingMessage ?: return
        val armed = armedSignature ?: return
        if (signatureOf(incoming, draft) != armed) clearWarningKeepingContext()
    }

    /** Keeps context active so the edited draft will be checked again. */
    fun keepEditing() {
        clearWarningKeepingContext()
    }

    /** Suppresses the advisory for this exact context and draft only. */
    fun dismissWarning() {
        dismissedSignature = armedSignature
        clearWarningKeepingContext()
    }

    /** Lets the next Send attempt bypass completeness analysis exactly once. */
    fun allowNextSend() {
        bypassNextSend = true
        clearWarningKeepingContext()
    }

    private fun clearWarningKeepingContext() {
        armedSignature = null
        if (_state.value.warning != null) {
            _state.value = _state.value.copy(warning = null)
        }
    }

    private fun previewOf(text: String): String {
        val singleLine = text.replace(Regex("\\s+"), " ").trim()
        return if (singleLine.length <= MAX_PREVIEW_CHARS) {
            singleLine
        } else {
            singleLine.take(MAX_PREVIEW_CHARS - 1).trimEnd() + "…"
        }
    }

    /** Ephemeral comparison only; raw draft text is never retained in state. */
    private fun signatureOf(incoming: String, draft: String): Int =
        31 * incoming.hashCode() + draft.hashCode()
}
