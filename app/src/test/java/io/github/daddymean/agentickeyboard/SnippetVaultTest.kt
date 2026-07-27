package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.db.CustomCommand
import io.github.daddymean.agentickeyboard.db.SavedSnippet
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import io.github.daddymean.agentickeyboard.util.SnippetListCodec
import io.github.daddymean.agentickeyboard.util.SnippetMatchField
import io.github.daddymean.agentickeyboard.util.SnippetRecallCommand
import io.github.daddymean.agentickeyboard.util.SnippetVaultAction
import io.github.daddymean.agentickeyboard.util.SnippetVaultEntry
import io.github.daddymean.agentickeyboard.util.SnippetVaultSearch
import io.github.daddymean.agentickeyboard.util.SnippetVaultSource
import io.github.daddymean.agentickeyboard.util.toVaultEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetVaultTest {

    @Test
    fun parseRecall_acceptsOnlyDedicatedLeadingCommands() {
        val vault = SnippetVaultSearch.parseRecall("   /v invoice balance ")
        assertEquals(SnippetRecallCommand.VAULT, vault?.command)
        assertEquals("invoice balance", vault?.query)

        val find = SnippetVaultSearch.parseRecall("/find return policy")
        assertEquals(SnippetRecallCommand.FIND, find?.command)
        assertEquals("return policy", find?.query)

        assertNull(SnippetVaultSearch.parseRecall("message /v invoice"))
        assertNull(SnippetVaultSearch.parseRecall("/firm invoice"))
        assertNull(SnippetVaultSearch.parseRecall("/vault invoice"))
    }

    @Test
    fun exactAliasOutranksBodyOnlyMatch() {
        val exactAlias = entry(
            id = "alias",
            title = "Payment reminder",
            content = "Following up on the remaining balance.",
            aliases = listOf("invoice")
        )
        val bodyOnly = entry(
            id = "body",
            title = "Accounting note",
            content = "Please review the invoice before Friday.",
            usageCount = 100
        )

        val matches = SnippetVaultSearch.search(listOf(bodyOnly, exactAlias), "invoice")

        assertEquals("alias", matches.first().entry.stableId)
        assertTrue(SnippetMatchField.ALIAS in matches.first().matchedOn)
    }

    @Test
    fun tagAndAccentMatchingAreNormalized() {
        val entry = entry(
            id = "cafe",
            title = "Café pickup",
            content = "Your order is ready.",
            tags = listOf("résumé", "customer service")
        )

        assertEquals("cafe", SnippetVaultSearch.search(listOf(entry), "cafe").single().entry.stableId)
        val tagMatch = SnippetVaultSearch.search(listOf(entry), "resume").single()
        assertTrue(SnippetMatchField.TAG in tagMatch.matchedOn)
    }

    @Test
    fun emptyQueryUsesPopularityThenRecencyWithStableTies() {
        val recent = entry(id = "recent", title = "Recent", content = "R", usageCount = 2, lastUsedAt = 20)
        val popular = entry(id = "popular", title = "Popular", content = "P", usageCount = 8, lastUsedAt = 1)
        val older = entry(id = "older", title = "Older", content = "O", usageCount = 2, lastUsedAt = 10)

        val matches = SnippetVaultSearch.search(listOf(recent, older, popular), "")

        assertEquals(listOf("popular", "recent", "older"), matches.map { it.entry.stableId })
    }

    @Test
    fun rankingIsDeterministicAcrossInputOrder() {
        val first = entry(id = "a", title = "Address A", content = "One")
        val second = entry(id = "b", title = "Address B", content = "Two")

        val forward = SnippetVaultSearch.search(listOf(first, second), "address")
        val reverse = SnippetVaultSearch.search(listOf(second, first), "address")

        assertEquals(forward.map { it.entry.stableId }, reverse.map { it.entry.stableId })
        assertEquals(listOf("a", "b"), forward.map { it.entry.stableId })
    }

    @Test
    fun resultLimitIsBoundedAndZeroReturnsNothing() {
        val entries = (1..30).map { entry("$it", "Invoice $it", "Body $it") }

        assertTrue(SnippetVaultSearch.search(entries, "invoice", limit = 0).isEmpty())
        assertEquals(20, SnippetVaultSearch.search(entries, "invoice", limit = 100).size)
    }

    @Test
    fun listCodecTrimsAndDeduplicatesCaseInsensitively() {
        val encoded = SnippetListCodec.encode(listOf(" Billing ", "billing", "VIP", "", "vip"))
        assertEquals("Billing\nVIP", encoded)
        assertEquals(listOf("Billing", "VIP"), SnippetListCodec.decode(encoded))
    }

    @Test
    fun legacyStoresMapIntoOneSearchContractWithoutChangingActions() {
        val saved = SavedSnippet(
            id = 4,
            title = "Return policy",
            content = "Returns are accepted within 14 days.",
            aliases = "returns",
            tags = "policy",
            usageCount = 3,
            lastUsedAt = 22,
            updatedAt = 20
        ).toVaultEntry()
        val shortcut = ShortcutTemplate(id = 5, shortcut = "addr", template = "123 Main Street").toVaultEntry()
        val command = CustomCommand(id = 6, token = "/invoice", instruction = "Rewrite as an invoice reminder").toVaultEntry()

        assertEquals(SnippetVaultSource.SAVED_SNIPPET, saved.source)
        assertEquals(SnippetVaultAction.INSERT_TEXT, saved.action)
        assertEquals(listOf("returns"), saved.aliases)

        assertEquals(SnippetVaultSource.SHORTCUT, shortcut.source)
        assertEquals(SnippetVaultAction.INSERT_TEXT, shortcut.action)
        assertEquals("123 Main Street", shortcut.content)

        assertEquals(SnippetVaultSource.CUSTOM_COMMAND, command.source)
        assertEquals(SnippetVaultAction.RUN_REWRITE, command.action)
        assertEquals(listOf("invoice"), command.aliases)
    }

    private fun entry(
        id: String,
        title: String,
        content: String,
        aliases: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        usageCount: Int = 0,
        lastUsedAt: Long = 0L,
        updatedAt: Long = 0L
    ) = SnippetVaultEntry(
        stableId = id,
        title = title,
        content = content,
        aliases = aliases,
        tags = tags,
        usageCount = usageCount,
        lastUsedAt = lastUsedAt,
        updatedAt = updatedAt
    )
}
