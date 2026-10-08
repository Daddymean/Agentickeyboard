package io.github.daddymean.agentickeyboard.ui

import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.util.TextExpansion

/**
 * Pure decision behind [KeyboardViewModel.resolveWordCommit], kept free of Room and
 * flows so the shortcut-before-correction order and the KEYBOARD-002 pause are
 * covered by plain JVM tests with seeded rules.
 */
object WordCommitResolver {
    fun resolve(
        word: String,
        shortcuts: List<ShortcutTemplate>,
        corrections: List<LearnedCorrection>,
        correctionsPaused: Boolean,
        expand: (String) -> TextExpansion.ExpandedText
    ): WordReplacement? {
        val normalized = word.lowercase().trim()
        if (normalized.isEmpty()) return null
        shortcuts.find { it.shortcut == normalized }?.let {
            val expanded = expand(it.template)
            return WordReplacement(
                expanded.text,
                fromLearnedRule = false,
                cursorOffset = expanded.cursorOffset
            )
        }
        // Shortcuts above still expand while learned corrections are paused.
        if (correctionsPaused) return null
        corrections.find { it.typo == normalized }?.let { correction ->
            // Preserve leading capitalization of the typed word
            val replacement = if (word.firstOrNull()?.isUpperCase() == true) {
                correction.correction.replaceFirstChar { it.uppercase() }
            } else {
                correction.correction
            }
            return WordReplacement(replacement, fromLearnedRule = true)
        }
        return null
    }
}
