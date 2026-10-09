package io.github.daddymean.agentickeyboard.util

/**
 * Decides when the keyboard capitalizes the next letter on its own (auto-shift at
 * a sentence start, and the first letter of a swiped word).
 *
 * KEYBOARD-006 keeps typed text exact in password, other sensitive and incognito
 * editors. KEYBOARD-007 extends that to auto-capitalization: those editors get
 * exactly the case the user chose, so a password that starts with a lowercase
 * letter is not silently capitalized. Explicit shift and caps lock still work.
 *
 * Pure logic so it can be unit-tested without an Android runtime.
 */
object AutoCapitalization {
    private val SENTENCE_ENDINGS = setOf('.', '!', '?')

    /** True when the caret sits at a position that should auto-capitalize. */
    fun isSentenceStart(text: String): Boolean {
        if (text.isEmpty() || text.endsWith("\n")) return true
        if (!text.last().isWhitespace()) return false
        val lastVisible = text.trimEnd().lastOrNull() ?: return true
        return lastVisible in SENTENCE_ENDINGS
    }

    /**
     * Whether the next letter should be capitalized automatically for [textBeforeCursor].
     * Never in a [sensitiveField], whatever the user's auto-capitalize setting.
     */
    fun applies(enabled: Boolean, sensitiveField: Boolean, textBeforeCursor: String): Boolean =
        enabled && !sensitiveField && isSentenceStart(textBeforeCursor)
}
