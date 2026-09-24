package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.util.TextExpansion.ExpansionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.Locale
import java.util.TimeZone
import org.junit.Test

class TextExpansionTest {

    /** Wednesday, 4 March 2026, 09:30 UTC. */
    private val fixedNow = GregorianCalendar(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(2026, Calendar.MARCH, 4, 9, 30, 0)
    }.timeInMillis

    private fun context(clipboard: String? = null) = ExpansionContext(
        now = fixedNow,
        clipboard = clipboard,
        locale = Locale.US,
        timeZone = TimeZone.getTimeZone("UTC")
    )

    private fun expand(template: String, clipboard: String? = null) =
        TextExpansion.expand(template, context(clipboard))

    @Test
    fun `text without tokens is returned untouched`() {
        val result = expand("On my way!")
        assertEquals("On my way!", result.text)
        assertNull(result.cursorOffset)
    }

    @Test
    fun `date and time use readable defaults`() {
        assertEquals("Mar 4, 2026", expand("{date}").text)
        // Assert loosely: the AM/PM marker's exact form varies with the JDK's CLDR data.
        assertTrue(expand("{time}").text.startsWith("9:30"))
        assertTrue(expand("{datetime}").text.startsWith("Mar 4, 2026 9:30"))
    }

    @Test
    fun `a custom pattern formats the moment`() {
        assertEquals("2026-03-04", expand("{date:yyyy-MM-dd}").text)
        assertEquals("Wednesday", expand("{date:EEEE}").text)
        assertEquals("09:30", expand("{time:HH:mm}").text)
    }

    @Test
    fun `offsets shift the moment by each supported unit`() {
        assertEquals("Mar 5, 2026", expand("{date+1d}").text)
        assertEquals("Mar 3, 2026", expand("{date-1d}").text)
        assertEquals("Mar 11, 2026", expand("{date+1w}").text)
        assertEquals("Apr 4, 2026", expand("{date+1mo}").text)
        assertEquals("Mar 4, 2027", expand("{date+1y}").text)
        assertEquals("10:00", expand("{time+30min:HH:mm}").text)
        assertEquals("08:30", expand("{time-1h:HH:mm}").text)
    }

    @Test
    fun `an offset combines with a pattern`() {
        // The everyday case: "let's meet {date+1d:EEEE}" -> "let's meet Thursday".
        assertEquals("Thursday", expand("{date+1d:EEEE}").text)
    }

    @Test
    fun `clipboard is substituted and is empty rather than absent when unset`() {
        assertEquals("Ref: ACME-123", expand("Ref: {clipboard}", clipboard = "ACME-123").text)
        assertEquals("Ref: ", expand("Ref: {clipboard}").text)
    }

    @Test
    fun `cursor marks a caret position and contributes no text`() {
        val result = expand("Hi {cursor},\n\nThanks!")
        assertEquals("Hi ,\n\nThanks!", result.text)
        assertEquals(3, result.cursorOffset)
    }

    @Test
    fun `the first cursor wins when a template declares several`() {
        val result = expand("a{cursor}b{cursor}c")
        assertEquals("abc", result.text)
        assertEquals(1, result.cursorOffset)
    }

    @Test
    fun `doubled braces produce literal braces`() {
        assertEquals("{date}", expand("{{date}}").text)
        assertEquals("{\"a\": 1}", expand("{{\"a\": 1}}").text)
    }

    @Test
    fun `unknown and malformed tokens survive verbatim`() {
        // Losing a user's text would be far worse than leaving it as typed.
        assertEquals("{foo}", expand("{foo}").text)
        assertEquals("{date", expand("{date").text)
        assertEquals("{}", expand("{}").text)
        assertEquals("{date:}", expand("{date:}").text)
        assertEquals("{clipboard:x}", expand("{clipboard:x}").text)
        assertEquals("{cursor+1d}", expand("{cursor+1d}").text)
    }

    @Test
    fun `an invalid date pattern does not take the snippet down with it`() {
        assertEquals("{date:QQQQQQQ}", expand("{date:QQQQQQQ}").text)
    }

    @Test
    fun `a realistic template expands end to end`() {
        val result = expand(
            "Standup {date:yyyy-MM-dd}\n\nYesterday: {cursor}\nToday:\nBlockers: none",
            clipboard = null
        )
        assertEquals(
            "Standup 2026-03-04\n\nYesterday: \nToday:\nBlockers: none",
            result.text
        )
        assertEquals("Standup 2026-03-04\n\nYesterday: ".length, result.cursorOffset)
    }

    @Test
    fun `the editor hint advertises tokens until a template uses one`() {
        val hint = TextExpansion.editorHint("On my way!", context())
        assertTrue(hint.startsWith("Tokens:"))
        assertTrue(hint.contains("{date}"))
    }

    @Test
    fun `the editor hint previews what a dynamic template will insert`() {
        assertEquals(
            "Inserts: Standup 2026-03-04",
            TextExpansion.editorHint("Standup {date:yyyy-MM-dd}", context())
        )
    }

    @Test
    fun `the editor hint collapses newlines and truncates a long preview`() {
        assertEquals(
            "Inserts: line one line two",
            TextExpansion.editorHint("line one\nline two{cursor}", context())
        )
        val long = TextExpansion.editorHint("{date:yyyy}" + "x".repeat(200), context())
        assertTrue(long.length < 100)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun `a template that expands to nothing says so`() {
        assertEquals("Inserts nothing yet", TextExpansion.editorHint("{cursor}", context()))
    }

    @Test
    fun `hasTokens spots templates worth flagging as dynamic`() {
        assertTrue(TextExpansion.hasTokens("Due {date+1w}"))
        assertTrue(!TextExpansion.hasTokens("On my way!"))
    }
}
