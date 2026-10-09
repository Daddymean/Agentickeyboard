package io.github.daddymean.agentickeyboard.ui

import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.util.LearnedRuleFilter
import io.github.daddymean.agentickeyboard.util.TextExpansion

/**
 * Pure decision behind [KeyboardViewModel.resolveWordCommit], kept free of Room and
 * flows so the shortcut-before-correction order, the KEYBOARD-002 pause and the
 * KEYBOARD-006 sensitive-field rule are covered by plain JVM tests with seeded rules.
 */
object WordCommitResolver {
    fun resolve(
        word: String,
        shortcuts: List<ShortcutTemplate>,
        corrections: List<LearnedCorrection>,
        correctionsPaused: Boolean,
        expand: (String) -> TextExpansion.ExpandedText,
        sensitiveField: Boolean = false
    ): WordReplacement? {
        // KEYBOARD-006: password, other sensitive and incognito editors keep exactly
        // what was typed. Neither shortcuts nor learned corrections may rewrite it
        // (a passphrase containing "teh" or "brb" must not change, and a shortcut
        // template must not splice {clipboard} or other text into a secret).
        if (sensitiveField) return null
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
        // KEYBOARD-008: "teh," and "teh." match the rule for "teh"; the punctuation is kept.
        val (core, trailing) = LearnedRuleFilter.splitTrailingPunctuation(word.trim())
        val key = core.lowercase()
        if (key.isEmpty()) return null
        corrections.find { it.typo == key }?.let { correction ->
            // Preserve leading capitalization of the typed word
            val replacement = if (core.firstOrNull()?.isUpperCase() == true) {
                correction.correction.replaceFirstChar { it.uppercase() }
            } else {
                correction.correction
            }
            return WordReplacement(replacement + trailing, fromLearnedRule = true)
        }
        return null
    }
}
