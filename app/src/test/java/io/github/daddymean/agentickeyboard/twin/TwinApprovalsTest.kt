package io.github.daddymean.agentickeyboard.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** KEYBOARD-045: approval gate 1a. Gated operations are blocked unless a fresh ticket exists. */
class TwinApprovalsTest {
    private var now = 1_000L
    private val approvals = TwinApprovals(clock = { now }, ttlMillis = 60_000L)

    @Test fun everyGatedActionIsBlockedByDefault() {
        for (a in TwinApprovals.GATED) assertFalse("$a must be blocked without approval", approvals.consume(a))
    }

    @Test fun ticketIsSingleUse() {
        approvals.issue(TwinAction.EXPORT)
        assertTrue(approvals.consume(TwinAction.EXPORT))
        assertFalse(approvals.consume(TwinAction.EXPORT))
    }

    @Test fun ticketCoversOnlyItsOwnAction() {
        approvals.issue(TwinAction.SEAL_CAPSULE)
        assertFalse(approvals.consume(TwinAction.DELETE_CAPSULE))
        assertFalse(approvals.consume(TwinAction.SAVE_RELEASE_ARCHIVE))
        assertTrue(approvals.consume(TwinAction.SEAL_CAPSULE))
    }

    @Test fun expiredTicketIsRefusedAndRemoved() {
        approvals.issue(TwinAction.SAVE_RELEASE_ARCHIVE)
        now += 60_001L
        assertFalse(approvals.consume(TwinAction.SAVE_RELEASE_ARCHIVE))
        now = 1_000L
        assertFalse(approvals.consume(TwinAction.SAVE_RELEASE_ARCHIVE))
    }

    @Test fun requireThrowsWithoutTicket() {
        try { approvals.require(TwinAction.DELETE_CAPSULE); fail("should block") } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("wasn't approved"))
        }
    }

    @Test fun clearRevokesAll() {
        TwinApprovals.GATED.forEach(approvals::issue)
        approvals.clear()
        for (a in TwinApprovals.GATED) assertFalse(approvals.consume(a))
    }

    @Test fun gatedActionsAreSensitiveOrDangerousOrWrite() {
        assertEquals(setOf(TwinAction.EXPORT, TwinAction.SAVE_RELEASE_ARCHIVE, TwinAction.SAVE_SHARE_CARDS, TwinAction.SEAL_CAPSULE, TwinAction.DELETE_CAPSULE), TwinApprovals.GATED)
        for (a in TwinApprovals.GATED) assertTrue(a.risk != TwinActionPolicy.Risk.READ)
    }

    /** Source guard: every gated action has a require() at its operation site, and only the gate issues tickets. */
    @Test fun operationSitesRequireTickets() {
        val root = TwinInsertionPathGuardTest.mainRoot()
        val all = root.walkTopDown().filter { it.extension == "kt" }.toList()
        val src = all.joinToString("\n") { it.readText() }
        for (a in TwinApprovals.GATED) assertTrue("missing require for $a", src.contains("TwinApprovals.shared.require(TwinAction.${a.name})"))
        val issuers = all.filter { it.readText().contains(".issue(") && it.readText().contains("TwinApprovals") }.map { it.name }.toSet()
        assertEquals(setOf("TwinActionGate.kt"), issuers)
    }
}
