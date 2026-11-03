package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectGlossaryTest {

    @Test fun parsesLinesCommasAndDedupesCaseInsensitively() {
        assertEquals(listOf("Vault", "Nexus", "MetalSpec"), ProjectGlossary.parse("Vault\nNexus, bad|term\nvault;MetalSpec"))
    }

    @Test fun dropsSecretsContactsAndLongNumbers() {
        val terms = ProjectGlossary.parse("keith@example.com\nhttps://x.io\n4111111111111111\nghp_abcdefghijklmnopqrstuvwxyz0123456789\nSKU-1042")
        assertEquals(listOf("SKU-1042"), terms)
    }

    @Test fun capsTermCountAndLength() {
        val raw = (1..300).joinToString("\n") { "term$it" } + "\n" + "x".repeat(41)
        val terms = ProjectGlossary.parse(raw)
        assertEquals(ProjectGlossary.MAX_TERMS, terms.size)
        assertFalse(terms.any { it.length > ProjectGlossary.MAX_TERM_LENGTH })
    }

    @Test fun guardIsLowercaseWords() {
        assertEquals(setOf("agentic", "keyboard", "nexus"), ProjectGlossary.autoFixGuard(listOf("Agentic Keyboard", "Nexus")))
    }

    @Test fun glossaryWordIsNotAutoFixed() {
        val spelling = LocalSpelling(listOf("next", "nexus", "the"))
        val guard = ProjectGlossary.autoFixGuard(ProjectGlossary.parse("Nikola"))
        assertNull(AutoFix.fix("nikola", "", spelling, guard))
    }

    @Test fun suggestionsByPrefix() {
        val terms = listOf("Agentickeyboard", "Agent board", "Ai-twin")
        assertEquals(listOf("Agent board", "Agentickeyboard"), ProjectGlossary.suggestions("ag", terms))
        assertTrue(ProjectGlossary.suggestions("a", terms).isEmpty())
    }
}
