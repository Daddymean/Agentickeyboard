package com.example.ui

import com.example.util.WritingQualityMeter

/**
 * Smart context-aware clipboard actions.
 * Auto-detects content type and suggests best AI actions.
 */
object ContextAwareClipboard {
    fun suggestActions(clipText: String): List<String> {
        val meter = WritingQualityMeter.assess(clipText)
        return when {
            clipText.contains("@") && clipText.contains(".") -> listOf("Professional reply", "Summarize email")
            clipText.length > 200 -> listOf("Summarize", "Explain in plain language")
            clipText.contains("code") || clipText.contains("function") -> listOf("Explain code", "Fix bugs")
            else -> listOf("Reply ideas", "Translate", "Tone analysis")
        }
    }
}
