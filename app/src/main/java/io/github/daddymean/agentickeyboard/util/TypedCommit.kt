package io.github.daddymean.agentickeyboard.util

import android.view.inputmethod.InputConnection

/**
 * KEYBOARD-024: an [InputConnection] that tells typing apart from insertion. The
 * service hands the keyboard such a connection; anything else commits normally.
 *
 * The default, plain `commitText`, treats a single code point as one typed key and
 * **anything longer as an insertion** (never typing). A multi-character commit is
 * typing only when the caller says so with [commitTypedText]: a swipe word, an
 * auto-fix of the word just typed, the double-space period. Pastes, AI results,
 * suggestions, snippets, smart replies and other non-key text use
 * [commitInsertedText] (or the plain default).
 */
interface TypingAwareConnection {
    fun commitTypedText(text: CharSequence, newCursorPosition: Int): Boolean
    fun commitInsertedText(text: CharSequence, newCursorPosition: Int): Boolean
}

/** Commits text the user typed (a key, swipe word or auto-fix of a typed word). */
fun InputConnection.commitTyped(text: CharSequence, newCursorPosition: Int): Boolean =
    (this as? TypingAwareConnection)?.commitTypedText(text, newCursorPosition) ?: commitText(text, newCursorPosition)

/** Commits text the user did not type here (paste, AI, suggestion, snippet, reply chip). */
fun InputConnection.commitInserted(text: CharSequence, newCursorPosition: Int): Boolean =
    (this as? TypingAwareConnection)?.commitInsertedText(text, newCursorPosition) ?: commitText(text, newCursorPosition)

/**
 * KEYBOARD-024 (Hax decision, 2026-10-10 17:19 PT): a suggestion-strip pick is Keith's own
 * choice, built from his typed prefix plus the on-device dictionary or his vocabulary, so it
 * counts as typing, but only when bounded: it completes or corrects the word being typed
 * ([currentWord] is non-empty), the chip was generated for exactly that word
 * ([generatedFor], null for next-word predictions and empty-field prompts; Codex review
 * on 6de6407, r4239778214: a stale prediction chip tapped after typing a character must
 * not pass as a completion), it replaces exactly that word, and it is one word
 * ([isBoundedWordPick]) followed by one space. Anything else is committed as inserted text.
 */
fun InputConnection.commitSuggestionPick(currentWord: String, suggestion: String, generatedFor: String?) {
    deleteSurroundingText(currentWord.length, 0)
    if (TypedPicks.isTypedWordPick(currentWord, suggestion, generatedFor)) {
        commitTyped("$suggestion ", 1) // twin: typed (suggestion pick for the current word)
    } else {
        commitInserted("$suggestion ", 1) // twin: inserted (prediction / prompt / unbounded)
    }
}

/**
 * KEYBOARD-024 (Hax decision, 17:19 PT): one emoji picked from an emoji panel counts as
 * typing. Only a single emoji (one emoji, including skin tone, ZWJ, flag and keycap
 * sequences) qualifies; anything else is committed as inserted text. The keyboard has no
 * emoji panel yet: this is the declared path for one. Single-code-point emoji typed on a
 * key already count as one key.
 */
fun InputConnection.commitPickedEmoji(emoji: String) {
    if (TypedPicks.isSingleEmoji(emoji)) {
        commitTyped(emoji, 1) // twin: typed (single emoji from an emoji panel)
    } else {
        commitInserted(emoji, 1) // twin: inserted (not a single emoji)
    }
}

object TypedPicks {
    /** Longest suggestion accepted as one typed word. */
    const val MAX_WORD_CHARS = 48

    /** A pick that completes or corrects the word being typed, from a chip made for that word. */
    fun isTypedWordPick(currentWord: String, suggestion: String, generatedFor: String?): Boolean =
        currentWord.isNotEmpty() && generatedFor == currentWord && isBoundedWordPick(suggestion)

    /** One word: no whitespace, at least one letter or digit, bounded length. */
    fun isBoundedWordPick(suggestion: String): Boolean =
        suggestion.isNotEmpty() && suggestion.length <= MAX_WORD_CHARS &&
            suggestion.none { it.isWhitespace() } && suggestion.any { it.isLetterOrDigit() }

    /**
     * Exactly one emoji: an emoji base with optional VS16 and skin-tone modifier, joined to
     * further such parts by ZWJ; or one regional-indicator flag pair; or one keycap
     * (`[0-9#*]` + VS16? + U+20E3); or a tag sequence (black flag + tags + cancel tag).
     */
    fun isSingleEmoji(text: String): Boolean {
        if (text.isEmpty() || text.length > 32) return false
        val cps = text.codePoints().toArray()
        // Flag: two regional indicators.
        if (cps.size == 2 && cps.all { it in 0x1F1E6..0x1F1FF }) return true
        // Keycap.
        if (cps.last() == 0x20E3) {
            val base = cps.first()
            val mid = cps.drop(1).dropLast(1)
            return (base in '0'.code..'9'.code || base == '#'.code || base == '*'.code) &&
                (mid.isEmpty() || mid == listOf(0xFE0F))
        }
        // Tag sequence (subdivision flags).
        if (cps.first() == 0x1F3F4 && cps.size > 2 && cps.last() == 0xE007F) {
            return cps.drop(1).dropLast(1).all { it in 0xE0020..0xE007E }
        }
        // Base (VS16)? (skin)? ( ZWJ base (VS16)? (skin)? )*
        var i = 0
        var parts = 0
        while (i < cps.size) {
            if (parts > 0) {
                if (cps[i] != 0x200D) return false
                i++
                if (i >= cps.size) return false
            }
            if (!isEmojiBase(cps[i])) return false
            i++
            if (i < cps.size && cps[i] == 0xFE0F) i++
            if (i < cps.size && cps[i] in 0x1F3FB..0x1F3FF) i++
            if (i < cps.size && cps[i] == 0xFE0F) i++
            parts++
        }
        return parts in 1..8
    }

    private fun isEmojiBase(cp: Int): Boolean =
        cp in 0x1F300..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp in 0x1F000..0x1F2FF ||
            cp == 0x00A9 || cp == 0x00AE || cp == 0x203C || cp == 0x2049 || cp == 0x2122 ||
            cp == 0x2139 || cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299
}
