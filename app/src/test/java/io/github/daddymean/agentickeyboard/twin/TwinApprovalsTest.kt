package io.github.daddymean.agentickeyboard.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** KEYBOARD-045: approval gate 1a + single-run nonce. Gated ops need the exact ticket. */
class TwinApprovalsTest {
    private var now = 1_000L
    private var seq = 0
    private val approvals = TwinApprovals(
        clock = { now },
        ttlMillis = 60_000L,
        newNonce = { "nonce-${++seq}" },
    )

    @Test fun everyGatedActionIsBlockedByDefault() {
        for (a in TwinApprovals.GATED) {
            assertFalse("$a must be blocked without approval", approvals.consume(a, "missing"))
        }
    }

    @Test fun ticketIsSingleUseForItsNonce() {
        val n = approvals.issue(TwinAction.EXPORT)
        assertTrue(approvals.consume(TwinAction.EXPORT, n))
        assertFalse(approvals.consume(TwinAction.EXPORT, n))
    }

    @Test fun ticketCoversOnlyItsOwnAction() {
        val n = approvals.issue(TwinAction.SEAL_CAPSULE)
        assertFalse(approvals.consume(TwinAction.DELETE_CAPSULE, n))
        assertFalse(approvals.consume(TwinAction.SAVE_RELEASE_ARCHIVE, n))
        assertTrue(approvals.consume(TwinAction.SEAL_CAPSULE, n))
    }

    @Test fun differentRunsGetDifferentNoncesAndDoNotShare() {
        val a = approvals.issue(TwinAction.EXPORT)
        val b = approvals.issue(TwinAction.EXPORT)
        assertNotEquals(a, b)
        assertTrue(approvals.consume(TwinAction.EXPORT, a))
        // Second run's ticket is still valid for its own nonce only.
        assertFalse(approvals.consume(TwinAction.EXPORT, a))
        assertTrue(approvals.consume(TwinAction.EXPORT, b))
    }

    @Test fun wrongNonceCannotConsumeEvenForSameAction() {
        approvals.issue(TwinAction.EXPORT)
        assertFalse(approvals.consume(TwinAction.EXPORT, "forged-nonce"))
    }

    @Test fun expiredTicketIsRefusedAndRemoved() {
        val n = approvals.issue(TwinAction.SAVE_RELEASE_ARCHIVE)
        now += 60_001L
        assertFalse(approvals.consume(TwinAction.SAVE_RELEASE_ARCHIVE, n))
        now = 1_000L
        assertFalse(approvals.consume(TwinAction.SAVE_RELEASE_ARCHIVE, n))
    }

    @Test fun requireThrowsWithoutMatchingTicket() {
        try {
            approvals.require(TwinAction.DELETE_CAPSULE, "nope")
            fail("should block")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("wasn't approved"))
        }
    }

    @Test fun clearRevokesAll() {
        val nonces = TwinApprovals.GATED.map { approvals.issue(it) }
        approvals.clear()
        TwinApprovals.GATED.zip(nonces).forEach { (a, n) ->
            assertFalse(approvals.consume(a, n))
        }
    }

    @Test fun gatedActionsAreSensitiveOrDangerousOrWrite() {
        assertEquals(
            setOf(
                TwinAction.EXPORT,
                TwinAction.SAVE_RELEASE_ARCHIVE,
                TwinAction.SAVE_SHARE_CARDS,
                TwinAction.SEAL_CAPSULE,
                TwinAction.DELETE_CAPSULE,
            ),
            TwinApprovals.GATED,
        )
        for (a in TwinApprovals.GATED) assertTrue(a.risk != TwinActionPolicy.Risk.READ)
    }

    /** Source guard: every gated action has require(action, nonce) at its site; only the gate issues. */
    @Test fun operationSitesRequireTickets() {
        val root = TwinInsertionPathGuardTest.mainRoot()
        val all = root.walkTopDown().filter { it.extension == "kt" }.toList()
        val src = all.joinToString("\n") { it.readText() }
        for (a in TwinApprovals.GATED) {
            assertTrue(
                "missing require for $a",
                src.contains("TwinApprovals.shared.require(TwinAction.${a.name}"),
            )
        }
        val issuers = all.filter {
            val t = it.readText()
            t.contains(".issue(") && t.contains("TwinApprovals")
        }.map { it.name }.toSet()
        assertEquals(setOf("TwinActionGate.kt"), issuers)
    }
}
