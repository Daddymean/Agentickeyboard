package io.github.daddymean.agentickeyboard.twin

import android.view.inputmethod.InputConnection
import io.github.daddymean.agentickeyboard.util.TypedPicks
import io.github.daddymean.agentickeyboard.util.commitInserted
import io.github.daddymean.agentickeyboard.util.commitPickedEmoji
import io.github.daddymean.agentickeyboard.util.commitSuggestionPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/**
 * KEYBOARD-024, Hax decision 2026-10-10 17:19 PT: suggestion-strip picks for the current
 * word and single emoji from an emoji panel are Keith's own typing; smart replies, tone
 * emoji chips, AI continuation and the other insertions still drop the draft.
 *
 * Drives the real [TwinTrackingInputConnection] over a fake editor that applies each
 * commit and delete, so the session sees the same updates the service reports.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwinTypedPicksTest {
    private class Editor {
        var field = ""
        val ic: InputConnection = Proxy.newProxyInstance(
            InputConnection::class.java.classLoader, arrayOf(InputConnection::class.java)
        ) { _, m, args ->
            when (m.name) {
                "commitText" -> field += args!![0].toString()
                "deleteSurroundingText" -> field = field.dropLast(args!![0] as Int)
            }
            when (m.returnType) { java.lang.Boolean.TYPE -> true; Integer.TYPE -> 0; else -> null }
        } as InputConnection
    }

    private class Run {
        val session = TwinCaptureSession().apply {
            start("com.whatsapp", "", TwinTyping.OPEN, chatField = true, textAfter = "", hasSelection = false)
        }
        val editor = Editor()
        var now = 1_000L
        val conn = TwinTrackingInputConnection(editor.ic, session) { now }

        fun report() { now += 5; session.onText(editor.field, now) }
        fun type(text: String) { for (ch in text) { now += 40; conn.commitText(ch.toString(), 1); report() } }
        fun send(): TwinCaptureSession.Candidate? { now += 40; return session.onSend(editor.field, now) }
        fun currentWord() = editor.field.takeLastWhile { !it.isWhitespace() }
    }

    @Test
    fun suggestionPickThenSendIsSaved() {
        val r = Run()
        r.type("see you tom")
        r.now += 40; r.conn.commitSuggestionPick(r.currentWord(), "tomorrow"); r.report()
        r.type("at 5")
        assertEquals("see you tomorrow at 5", r.send()?.text)

        // A correction of the current word counts the same way.
        val fix = Run()
        fix.type("teh")
        fix.now += 40; fix.conn.commitSuggestionPick(fix.currentWord(), "the"); fix.report()
        fix.type("plan")
        assertEquals("the plan", fix.send()?.text)

        // So does an app-side clear in a chat app afterwards.
        val clear = Run()
        clear.type("thx for the hel")
        clear.now += 40; clear.conn.commitSuggestionPick(clear.currentWord(), "help"); clear.report()
        clear.now += 2_000
        assertEquals("thx for the help", clear.session.onText("", clear.now)?.text)
    }

    @Test
    fun unboundedSuggestionPicksStayInserted() {
        // A next-word prediction (no current word) is not built from a typed prefix.
        val next = Run()
        next.type("see you ")
        next.now += 40; next.conn.commitSuggestionPick(next.currentWord(), "tomorrow"); next.report()
        assertNull(next.send())
        // A multi-word entry is never a word pick.
        val phrase = Run()
        phrase.type("see you to")
        phrase.now += 40; phrase.conn.commitSuggestionPick(phrase.currentWord(), "tomorrow at noon"); phrase.report()
        assertNull(phrase.send())
        assertFalse(TypedPicks.isBoundedWordPick("x".repeat(TypedPicks.MAX_WORD_CHARS + 1)))
        assertFalse(TypedPicks.isBoundedWordPick("..."))
        assertTrue(TypedPicks.isBoundedWordPick("don't"))
    }

    @Test
    fun emojiThenSendIsSaved() {
        for (emoji in listOf("\u2764\uFE0F", "\uD83D\uDC4D\uD83C\uDFFD", "\uD83D\uDE02", "\uD83D\uDC69\u200D\uD83D\uDCBB", "\uD83C\uDDFA\uD83C\uDDF8")) {
            val r = Run()
            r.type("love it ")
            r.now += 40; r.conn.commitPickedEmoji(emoji); r.report()
            assertEquals(emoji, "love it $emoji", r.send()?.text)
        }
        // Two emoji, or emoji with text, are not "one emoji".
        for (notOne in listOf("\uD83D\uDE02\uD83D\uDE02", "ok \uD83D\uDE02", "abc", "\u200D\uD83D\uDE02")) {
            assertFalse(notOne, TypedPicks.isSingleEmoji(notOne))
            val r = Run()
            r.type("hi ")
            r.now += 40; r.conn.commitPickedEmoji(notOne); r.report()
            assertNull(notOne, r.send())
        }
    }

    @Test
    fun smartReplyThenSendIsStillNeverSaved() {
        // Smart replies (and tone-emoji chips, AI continuation...) go through onInsertText -> commitInserted.
        val r = Run()
        r.now += 40; r.conn.commitInserted("Sounds good, see you then!", 1); r.report()
        assertNull(r.send())
        val typedFirst = Run()
        typedFirst.type("yes ")
        typedFirst.now += 40; typedFirst.conn.commitInserted("Sounds good, see you then!", 1); typedFirst.report()
        assertNull(typedFirst.send())
        // A tone-emoji chip stays an insertion even though it is a single emoji.
        val tone = Run()
        tone.type("great ")
        tone.now += 40; tone.conn.commitInserted("\uD83C\uDF89", 1); tone.report()
        assertNull(tone.send())
    }
}
