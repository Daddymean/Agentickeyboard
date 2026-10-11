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
