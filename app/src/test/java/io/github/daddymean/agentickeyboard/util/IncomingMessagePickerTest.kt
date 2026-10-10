package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.util.IncomingMessagePicker.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** KEYBOARD-022: picking the other person's latest message from screen positions. */
class IncomingMessagePickerTest {
    private val left = 0
    private val right = 1000
    private fun them(text: String, top: Int) = Block(text, top, 40, 700)
    private fun me(text: String, top: Int) = Block(text, top, 320, 960)
    private fun centred(text: String, top: Int) = Block(text, top, 430, 570)

    @Test
    fun picksTheLowestLeftBubbleEvenWhenMyReplyIsLast() {
        val blocks = listOf(
            them("hey", 100), me("hi!", 200), centred("10:42 PM", 260),
            them("why didn't you call", 300), me("sorry, was driving", 400)
        )
        assertEquals("why didn't you call", IncomingMessagePicker.latestIncoming(blocks, left, right))
    }

    @Test
    fun joinsLinesOfTheSameBubble() {
        val blocks = listOf(me("ok", 100), them("Running late,", 300), them("be there at 8", 340))
        assertEquals("Running late, be there at 8", IncomingMessagePicker.latestIncoming(blocks, left, right))
    }

    @Test
    fun ignoresTimestampsAndStatusLines() {
        val blocks = listOf(me("see you", 100), them("Today", 150), them("Love you", 200), them("9:15 AM", 240))
        assertEquals("Love you", IncomingMessagePicker.latestIncoming(blocks, left, right))
    }

    @Test
    fun unclearLayoutsGiveNothing() {
        // Full-width text (an email or a feed) is neither side.
        assertNull(IncomingMessagePicker.latestIncoming(
            listOf(Block("Dear Keith", 100, 20, 990), Block("Regards", 200, 20, 990)), left, right))
        // Only one side present: cannot tell whose messages these are.
        assertNull(IncomingMessagePicker.latestIncoming(listOf(them("a", 100), them("b", 200)), left, right))
        assertNull(IncomingMessagePicker.latestIncoming(listOf(me("a", 100), me("b", 200)), left, right))
        assertNull(IncomingMessagePicker.latestIncoming(emptyList(), left, right))
        assertNull(IncomingMessagePicker.latestIncoming(listOf(them("a", 1), me("b", 2)), 10, 10))
    }

    @Test
    fun respectsAWindowThatDoesNotStartAtZero() {
        // Split screen: the host occupies the right half.
        val blocks = listOf(Block("thanks!!", 100, 1020, 1350), Block("np", 200, 1160, 1480))
        assertEquals("thanks!!", IncomingMessagePicker.latestIncoming(blocks, 1000, 1500))
    }

    @Test
    fun capsTheLength() {
        val long = "a".repeat(5_000)
        assertEquals(IncomingMessagePicker.MAX_CHARS,
            IncomingMessagePicker.latestIncoming(listOf(me("x", 1), them(long, 100)), left, right)!!.length)
    }
}
