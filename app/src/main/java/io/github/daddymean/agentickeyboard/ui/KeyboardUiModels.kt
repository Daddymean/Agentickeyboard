package io.github.daddymean.agentickeyboard.ui

import io.github.daddymean.agentickeyboard.util.CommittedEditUndo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * What a committed word should become, and whether a learned typo rule produced it.
 * [cursorOffset] is where the caret should land inside [replacement] when the
 * expanded template declared a {cursor} marker.
 */
data class WordReplacement(
    val replacement: String,
    val fromLearnedRule: Boolean,
    val cursorOffset: Int? = null,
    /** KEYBOARD-011: a high-confidence typo fix, not a stored rule. */
    val fromAutoFix: Boolean = false
)

/**
 * A just-applied auto-correction that backspace can revert. [editorUndo] is an
 * exact-range revert, tried first. When the caret was left inside the replacement
 * ([cursorOffset] non-null) it is the only revert: a suffix match on the text before
 * the caret cannot find such an edit.
 */
data class AutoCorrectionUndo(
    val original: String,
    val replacement: String,
    val fromLearnedRule: Boolean,
    val editorUndo: CommittedEditUndo? = null,
    val cursorOffset: Int? = null,
    val fromAutoFix: Boolean = false
)

/** A just-applied AI result that backspace can revert; see [AutoCorrectionUndo]. */
/** An AI result waiting on the opt-in where/what confirm. */
data class PendingAiApply(val where: String, val preview: String)

data class AiApplyUndo(
    val original: String,
    val replacement: String,
    val editorUndo: CommittedEditUndo? = null,
    val cursorOffset: Int? = null
)

/** Local, on-device usage statistics shown in the Style Hub dashboard. */
data class UsageStats(
    val autoCorrections: Int = 0,
    val swipeWords: Int = 0,
    val aiApplies: Int = 0,
    val shortcutExpansions: Int = 0
)

/** Transient, local-only style match shown beside eligible AI writing results. */
data class VoiceMatchState(
    val percent: Int,
    val confidence: Int,
    val label: String,
    val signals: List<String>,
    val delta: Int? = null
)

/**
 * KEYBOARD-024: one suggestion-strip chip and the word its list was generated for
 * ([sourceWord], null for next-word predictions and empty-field prompts). Immutable and
 * published as one value with its list, so the click handler reads the source of the
 * chip that is actually rendered.
 */
/**
 * [label] is what the chip shows; it defaults to [word], the text the pick commits.
 * LAB-007 quick answers show "= 84" but commit "12*7=84", and set [quickAnswer].
 */
data class PredictionChip(
    val word: String,
    val sourceWord: String?,
    val label: String = word,
    /** A computed answer: never learned as vocabulary or a bigram when picked. */
    val quickAnswer: Boolean = false
) {
    companion object {
        fun unbound(words: List<String>): List<PredictionChip> = words.map { PredictionChip(it, null) }
    }
}
