package io.github.daddymean.agentickeyboard.util

/**
 * Refuses to apply an AI result to a draft that changed after the request.
 *
 * Apply replaces the selection or everything before the cursor. If the user kept
 * typing, moved the selection or switched text while the model was working, the
 * result describes text that is no longer there, and applying it would silently
 * overwrite the newer words. Undo is a fallback, not the protection.
 */
object AiApplyGuard {
    const val STALE_MESSAGE = "Draft changed since this result — run the action again on your current text"

    /**
     * True when [current] (the live selection or text before the cursor) no longer
     * matches [source], the text the result was generated from. Trailing
     * whitespace is ignored: a space typed after the request does not change
     * what the result is about.
     */
    fun isStale(source: String?, current: String): Boolean =
        source != null && source.trimEnd() != current.trimEnd()
}
