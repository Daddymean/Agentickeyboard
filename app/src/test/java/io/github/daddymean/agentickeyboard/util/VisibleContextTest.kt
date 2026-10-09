package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.*
import org.junit.Test

class VisibleContextTest {
    @Test fun filtersOtherAppsEditorsAndHiddenTextAndKeepsScreenOrder() {
        val nodes = listOf(
            VisibleTextNode("chat", "Second message", 20, 0),
            VisibleTextNode("keyboard", "Reply Coach", 1, 0),
            VisibleTextNode("chat", "Draft", 30, 0, excluded = true),
            VisibleTextNode("chat", "Offscreen", 40, 0, visible = false),
            VisibleTextNode("chat", "First message", 10, 0))
        assertEquals(listOf("First message", "Second message"),
            VisibleContextPolicy.build("chat", 1, nodes)!!.lines)
    }

    @Test fun boundsCaptureAndKeepsRepeatedMessages() {
        val repeated = List(45) { VisibleTextNode("chat", "same", it, 0) }
        val result = VisibleContextPolicy.build("chat", 1, repeated)!!
        assertEquals(40, result.lines.size)
        assertTrue(result.truncated)
        val long = VisibleContextPolicy.build("chat", 1,
            listOf(VisibleTextNode("chat", "x".repeat(10_000), 0, 0)))!!
        assertTrue(long.lines.joinToString("\n").length <= 8_000)
        assertTrue(long.truncated)
        assertNull(VisibleContextPolicy.build("chat", 1, List(257) { repeated[0] }))
    }

    @Test fun changedConversationSameAppOrWindowAndExpiryInvalidateLease() {
        val original = VisibleContext("chat", 1, listOf("Bring invoices?", "Meeting time?"))
        val lease = VisibleContextLease()
        lease.capture(original, 100)
        assertTrue(lease.matches(original, 101))
        assertFalse(lease.matches(original.copy(lines = listOf("Different conversation")), 101))
        assertFalse(lease.matches(original.copy(packageName = "other"), 101))
        assertFalse(lease.matches(original.copy(windowId = 2), 101))
        assertFalse(lease.matches(null, 101))
        assertFalse(lease.matches(original, 60_100))
        lease.clear()
        assertFalse(lease.matches(original, 101))
    }
}
