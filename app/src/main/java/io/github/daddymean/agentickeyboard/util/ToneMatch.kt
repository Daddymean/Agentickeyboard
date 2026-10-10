package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.util.IncomingSentiment.Mood

/**
 * KEYBOARD-023: one-tap "warmer / calmer" for the reply draft, driven by the
 * KEYBOARD-022 mood badge.
 *
 * The request is *distilled*: it holds the user's own draft plus the mood label
 * and its intensity, and has no field for the incoming message, so the other
 * person's words cannot reach Gemini through this path (Keith, 2026-10-10 01:59
 * and 03:30 PT). The draft still goes through the cloud redaction interceptor.
 */
object ToneMatch {
    enum class Target(val chip: String, val label: String) {
        /** Complimentary: their message is positive. */
        WARMER("Make it warmer", "Warmer"),
        /** Diffuser: their message is upset or tense. */
        CALMER("Calm it down", "Calmer")
    }

    data class Request(val draft: String, val target: Target, val mood: Mood, val intensity: Float)

    fun targetFor(mood: Mood): Target? = when (mood) {
        Mood.POSITIVE -> Target.WARMER
        Mood.NEGATIVE, Mood.TENSE -> Target.CALMER
        Mood.NEUTRAL -> null
    }

    /** Null when there is nothing to match: a neutral mood or an empty draft. */
    fun request(draft: String, badge: IncomingMoodSession.Badge): Request? {
        if (draft.isBlank()) return null
        val target = targetFor(badge.mood) ?: return null
        return Request(draft, target, badge.mood, badge.intensity)
    }

    /** The whole prompt sent to Gemini (cloud) or Nano (on device). */
    fun prompt(r: Request): String {
        val goal = when (r.target) {
            Target.WARMER -> "complimentary: warm and appreciative, matching their good mood, without gushing"
            Target.CALMER -> "de-escalating: calm and acknowledging, no blame or sarcasm, " +
                "and if it fits, one concrete next step"
        }
        return """
            The person the user is replying to seems ${r.mood.label.lowercase()} (intensity ${"%.1f".format(r.intensity)} of 1).
            Rewrite the user's reply draft so its tone is $goal.
            Keep the meaning, the language and roughly the length. Keep it sounding like the user, casual if they are casual.
            Do not invent facts, names or promises. Keep any [REDACTED] style placeholders exactly as they are.
            Output only the rewritten draft, with no quotes or explanation.
            Draft: "${r.draft}"
        """.trimIndent()
    }

    private val SHOUTED = Regex("\\b[A-Z]{3,}\\b")
    private val REPEATED_MARKS = Regex("([!?])[!?]+")
    private val EMOJI_OR_SYMBOL = Regex("[\\p{So}\\p{Sk}]")
    private val OPENERS = Regex("^(i hear you|i understand|i'm sorry|sorry|i get it)", RegexOption.IGNORE_CASE)
    private val BLAME = listOf(
        Regex("\\byou never\\b", RegexOption.IGNORE_CASE) to "it feels like you don't",
        Regex("\\byou always\\b", RegexOption.IGNORE_CASE) to "it feels like you often",
        Regex("\\bwhatever\\b", RegexOption.IGNORE_CASE) to "okay",
        Regex("\\bshut up\\b", RegexOption.IGNORE_CASE) to "let's pause",
        Regex("\\bcalm down\\b", RegexOption.IGNORE_CASE) to "let's slow down"
    )

    /**
     * Offline fallback when no on-device model is available: small, predictable
     * edits that never change what the user is saying.
     */
    fun template(r: Request): String {
        var text = r.draft.trim()
        if (text.isEmpty()) return r.draft
        when (r.target) {
            Target.CALMER -> {
                text = SHOUTED.replace(text) { m -> if (m.value == "OK") m.value else m.value.lowercase() }
                text = REPEATED_MARKS.replace(text) { it.groupValues[1] }
                text = text.replace("!", ".")
                BLAME.forEach { (pattern, softer) -> text = pattern.replace(text, softer) }
                if (!OPENERS.containsMatchIn(text)) {
                    text = "I hear you. " + text.replaceFirstChar { it.uppercaseChar() }
                }
            }
            Target.WARMER -> {
                text = REPEATED_MARKS.replace(text) { it.groupValues[1] }
                if (text.endsWith(".")) text = text.dropLast(1) + "!"
                else if (text.last().isLetterOrDigit()) text += "!"
                if (!EMOJI_OR_SYMBOL.containsMatchIn(text)) text += " 😊"
            }
        }
        return text
    }
}
