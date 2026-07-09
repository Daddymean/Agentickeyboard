package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.CommandPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandPaletteTest {

    @Test
    fun activeTokenRequiresLeadingSlash() {
        assertEquals("/firm", CommandPalette.activeToken("/firm please send it"))
        assertEquals("/fi", CommandPalette.activeToken("/fi"))
        assertEquals("/", CommandPalette.activeToken("/"))
        assertEquals("/firm", CommandPalette.activeToken("  /firm indented"))
        assertNull(CommandPalette.activeToken("hello /firm not leading"))
        assertNull(CommandPalette.activeToken(""))
        assertNull(CommandPalette.activeToken("plain text"))
    }

    @Test
    fun matchesFiltersByTokenPrefix() {
        assertEquals(CommandPalette.COMMANDS, CommandPalette.matches("/"))
        assertEquals(listOf("/firm"), CommandPalette.matches("/fi").map { it.token })
        assertEquals(listOf("/firm"), CommandPalette.matches("/FIRM ok").map { it.token })
        assertTrue(CommandPalette.matches("/zzz").isEmpty())
        assertTrue(CommandPalette.matches("no slash").isEmpty())
        // A full path-like token that is no command should not open the palette
        assertTrue(CommandPalette.matches("/home/user").isEmpty())
    }

    @Test
    fun marketplaceAndBoundaryCommandsAreBuiltIn() {
        val tokens = CommandPalette.COMMANDS.map { it.token }
        assertTrue(tokens.contains("/sell"))
        assertTrue(tokens.contains("/close"))
        assertTrue(tokens.contains("/counteroffer"))
        assertTrue(tokens.contains("/followup"))
        assertTrue(tokens.contains("/boundary"))
        assertTrue(tokens.contains("/extract"))
    }

    @Test
    fun everyCommandTokenMatchesItself() {
        for (cmd in CommandPalette.COMMANDS) {
            assertTrue(
                "expected ${cmd.token} to match itself",
                CommandPalette.matches("${cmd.token} text").contains(cmd)
            )
        }
    }

    @Test
    fun stripTokenRemovesLeadingTokenOnly() {
        assertEquals("please send it", CommandPalette.stripToken("/firm please send it"))
        assertEquals("", CommandPalette.stripToken("/firm"))
        assertEquals("", CommandPalette.stripToken("/firm   "))
        assertEquals("indented text", CommandPalette.stripToken("  /kind indented text"))
        assertEquals("no slash here", CommandPalette.stripToken("no slash here"))
    }
}
