package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.CommandPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCommandPaletteTest {

    private val custom = listOf(
        CommandPalette.Command("/boss", "Custom", CommandPalette.Action.REWRITE, "formal but friendly"),
        CommandPalette.Command("/firm", "Custom", CommandPalette.Action.REWRITE, "should never shadow the built-in")
    )

    @Test
    fun customCommandsListAfterBuiltIns() {
        val all = CommandPalette.matches("/", custom)
        assertEquals(CommandPalette.COMMANDS, all.take(CommandPalette.COMMANDS.size))
        assertTrue(all.any { it.token == "/boss" })
    }

    @Test
    fun customCommandsFollowMatchingBuiltInsForSharedPrefixes() {
        val hits = CommandPalette.matches("/bo tell him the deadline moved", custom)
        assertEquals(listOf("/boundary", "/boss"), hits.map { it.token })
        assertEquals("formal but friendly", hits.first { it.token == "/boss" }.instruction)
    }

    @Test
    fun builtInsWinTokenClashes() {
        val hits = CommandPalette.matches("/firm please send it", custom)
        assertEquals(listOf("/firm"), hits.map { it.token })
        assertEquals("firm, direct and confident", hits[0].instruction)
    }

    @Test
    fun emptyCustomListBehavesLikeBuiltInsOnly() {
        assertEquals(CommandPalette.matches("/f"), CommandPalette.matches("/f", emptyList()))
    }

    @Test
    fun normalizeTokenRules() {
        assertEquals("/boss", CommandPalette.normalizeToken("boss"))
        assertEquals("/boss", CommandPalette.normalizeToken(" /Boss "))
        assertNull(CommandPalette.normalizeToken(""))
        assertNull(CommandPalette.normalizeToken("/"))
        assertNull(CommandPalette.normalizeToken("two words"))
        assertNull(CommandPalette.normalizeToken("/a/b"))
    }
}
