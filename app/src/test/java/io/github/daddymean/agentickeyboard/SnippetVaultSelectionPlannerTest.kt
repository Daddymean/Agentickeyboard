package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.SnippetRecallCommand
import io.github.daddymean.agentickeyboard.util.SnippetRecallRequest
import io.github.daddymean.agentickeyboard.util.SnippetVaultAction
import io.github.daddymean.agentickeyboard.util.SnippetVaultEntry
import io.github.daddymean.agentickeyboard.util.SnippetVaultSearch
import io.github.daddymean.agentickeyboard.util.SnippetVaultSelectionPlan
import io.github.daddymean.agentickeyboard.util.SnippetVaultSelectionPlanner
import io.github.daddymean.agentickeyboard.util.SnippetVaultSource
import io.github.daddymean.agentickeyboard.util.savedSnippetIdOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnippetVaultSelectionPlannerTest {

    @Test
    fun recallParserSeparatesOptionalRewriteBody() {
        val request = SnippetVaultSearch.parseRecall(
            "/v boss :: Move tomorrow's meeting to three"
        )

        assertEquals(SnippetRecallCommand.VAULT, request?.command)
        assertEquals("boss", request?.query)
        assertEquals("Move tomorrow's meeting to three", request?.body)
    }

    @Test
    fun existingRecallSyntaxRemainsCompatible() {
        assertEquals(
            SnippetRecallRequest(SnippetRecallCommand.FIND, "invoice"),
            SnippetVaultSearch.parseRecall(" /find invoice ")
        )
    }

    @Test
    fun insertEntriesAlwaysRequireTapAndReturnTheirStoredText() {
        val plan = SnippetVaultSelectionPlanner.plan(
            request = SnippetRecallRequest(SnippetRecallCommand.VAULT, "address"),
            entry = SnippetVaultEntry(
                stableId = "snippet:4",
                title = "Shipping address",
                content = "123 Example Street"
            )
        )

        assertEquals(
            SnippetVaultSelectionPlan.InsertText("123 Example Street"),
            plan
        )
    }

    @Test
    fun customCommandWithoutBodyStagesNormalSlashCommand() {
        val plan = SnippetVaultSelectionPlanner.plan(
            request = SnippetRecallRequest(SnippetRecallCommand.VAULT, "boss"),
            entry = commandEntry()
        )

        assertEquals(SnippetVaultSelectionPlan.StageCommand("/boss"), plan)
    }

    @Test
    fun customCommandWithBodyRunsRewriteOnlyAfterSelection() {
        val plan = SnippetVaultSelectionPlanner.plan(
            request = SnippetRecallRequest(
                SnippetRecallCommand.VAULT,
                query = "boss",
                body = "Move the meeting to three"
            ),
            entry = commandEntry()
        )

        assertEquals(
            SnippetVaultSelectionPlan.RunRewrite(
                sourceText = "Move the meeting to three",
                instruction = "professional and concise"
            ),
            plan
        )
    }

    @Test
    fun savedSnippetIdIsAvailableOnlyForSavedSnippetSources() {
        val saved = SnippetVaultEntry(
            stableId = "snippet:42",
            title = "Address",
            content = "123 Example Street",
            source = SnippetVaultSource.SAVED_SNIPPET
        )
        val shortcut = saved.copy(
            stableId = "shortcut:42",
            source = SnippetVaultSource.SHORTCUT
        )

        assertEquals(42, saved.savedSnippetIdOrNull())
        assertNull(shortcut.savedSnippetIdOrNull())
        assertTrue(saved.savedSnippetIdOrNull()!! > 0)
    }

    private fun commandEntry() = SnippetVaultEntry(
        stableId = "command:7",
        title = "/boss",
        content = "professional and concise",
        action = SnippetVaultAction.RUN_REWRITE,
        source = SnippetVaultSource.CUSTOM_COMMAND
    )
}
