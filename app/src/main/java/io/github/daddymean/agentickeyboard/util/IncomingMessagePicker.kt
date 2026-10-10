package io.github.daddymean.agentickeyboard.util

/**
 * KEYBOARD-022: which visible text is the latest message *from the other person*.
 *
 * Generic screen text has no sender. Most chat apps put incoming bubbles on the
 * left and the user's own on the right, so the latest incoming message is the
 * lowest block that sits left: its left edge in the left 20% of the window and
 * its right edge short of the right 10%. Centred short lines (times, dates,
 * "Today") are skipped. If nothing looks like a left/right bubble layout, the
 * answer is null and no mood is shown. The result is only an estimate.
 */
object IncomingMessagePicker {
    data class Block(val text: String, val top: Int, val left: Int, val right: Int)

    fun latestIncoming(blocks: List<Block>, windowLeft: Int, windowRight: Int): String? {
        val width = windowRight - windowLeft
        if (width <= 0) return null
        val usable = blocks.filter { it.text.isNotBlank() && it.right > it.left && !looksLikeMeta(it.text) }
        fun leftAligned(b: Block) = b.left - windowLeft <= width * 0.20 && windowRight - b.right >= width * 0.10
        fun rightAligned(b: Block) = windowRight - b.right <= width * 0.20 && b.left - windowLeft >= width * 0.10
        val incoming = usable.filter { leftAligned(it) }
        val outgoing = usable.filter { rightAligned(it) && !leftAligned(it) }
        // Both sides must be present for the layout to read as a two-sided chat.
        if (incoming.isEmpty() || outgoing.isEmpty()) return null
        val latest = incoming.maxByOrNull { it.top } ?: return null
        // Consecutive lines of the same bubble (wrapped or split nodes) belong together.
        val sameBubble = incoming.filter { it.top <= latest.top && latest.top - it.top < MERGE_GAP_PX && it !== latest }
            .sortedBy { it.top }
        return (sameBubble + latest).joinToString(" ") { it.text.trim() }.take(MAX_CHARS)
    }

    private val META = Regex(
        "^(today|yesterday|now|just now|read|delivered|seen|sent|typing\\.*|\\d{1,2}:\\d{2}\\s*([ap]\\.?m\\.?)?|" +
            "(mon|tue|wed|thu|fri|sat|sun)[a-z]*\\.?( \\d{1,2}:\\d{2}\\s*([ap]\\.?m\\.?)?)?)$",
        RegexOption.IGNORE_CASE
    )

    private fun looksLikeMeta(text: String) = META.matches(text.trim())

    private const val MERGE_GAP_PX = 80
    const val MAX_CHARS = 1_000
}
