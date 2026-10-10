package io.github.daddymean.agentickeyboard.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TwinTextWindowTest {

    @Test
    fun shortTextNeedsNoExtraRead() {
        var reads = 0
        assertEquals("short", TwinTextWindow.expand("short", 1_000) { reads++; "unused" })
        assertEquals(0, reads)
    }

    @Test
    fun fullWindowReadsTheLargerOne() {
        val full = "a".repeat(3_000)
        var asked = 0
        val out = TwinTextWindow.expand(full.takeLast(1_000), 1_000) { asked = it; full }
        assertEquals(TwinTextWindow.CHARS, asked)
        assertEquals(full, out)
    }

    @Test
    fun failedOrShorterReadFallsBackToTheWindow() {
        val window = "b".repeat(1_000)
        assertEquals(window, TwinTextWindow.expand(window, 1_000) { null })
        assertEquals(window, TwinTextWindow.expand(window, 1_000) { error("editor gone") })
        assertEquals(window, TwinTextWindow.expand(window, 1_000) { "b" })
    }

    @Test
    fun aLongTypedMessageIsCapturedWhole() {
        // Simulates the service: a 3,000-character message typed key by key, with the
        // editor returning at most 1,000 chars normally and up to CHARS on the larger read.
        val session = TwinCaptureSession().apply {
            start("com.example.mail", "", TwinCaptureSession.Gate(true, false, false, false))
        }
        val message = StringBuilder()
        val words = listOf("Dear ", "kids, ", "remember ", "that ", "I ", "love ", "you. ")
        var i = 0
        var t = 0L
        while (message.length < 3_000) {
            for (ch in words[i++ % words.size]) {
                message.append(ch)
                val editor = message.toString()
                val window = editor.takeLast(1_000)
                session.onText(TwinTextWindow.expand(window, 1_000) { n -> editor.takeLast(n) }, t++)
            }
        }
        val candidate = session.onText("", t + 1)
        assertNotNull(candidate)
        assertEquals(message.toString().trim(), candidate!!.text)
        assertEquals(TwinTextFilter.MAX_CHARS.coerceAtMost(candidate.text.length), TwinTextFilter.prepare(candidate.text)!!.length)
    }
}
