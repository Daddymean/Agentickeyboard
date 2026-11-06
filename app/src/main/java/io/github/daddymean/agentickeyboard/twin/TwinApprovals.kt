package io.github.daddymean.agentickeyboard.twin

import java.security.SecureRandom

/**
 * KEYBOARD-045 (approval gate 1a + single-run nonce): single-use approval tickets
 * for gated twin actions, bound to a specific action *instance* (nonce), not only
 * the action type.
 *
 * The operation itself (writing an export, saving a release archive, sealing or
 * deleting a capsule) is blocked by default. It runs only if it can [consume] the
 * exact ticket that [TwinActionGate] issued after the policy allowed the action or
 * Keith tapped Allow. Approving one EXPORT does not unlock a different EXPORT run.
 * One ticket covers one nonce, once, and expires after [ttlMillis] (long enough for
 * the system file picker). Tickets live in memory only, so a restart or process
 * death revokes them. Pure logic with an injected clock and nonce source.
 */
class TwinApprovals(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = TTL_MILLIS,
    private val newNonce: () -> String = Companion::randomNonce,
) {
    private data class Ticket(val action: TwinAction, val expiresAt: Long)
    private val tickets = HashMap<String, Ticket>()

    /** Called only by the gate once the action is allowed. Returns the run nonce. */
    @Synchronized fun issue(action: TwinAction): String {
        val nonce = newNonce()
        tickets[nonce] = Ticket(action, clock() + ttlMillis)
        return nonce
    }

    /**
     * True once for this exact [nonce] + [action] while unexpired; false (blocked)
     * otherwise. A ticket for the same action type with a different nonce does not match.
     */
    @Synchronized fun consume(action: TwinAction, nonce: String): Boolean {
        val ticket = tickets[nonce] ?: return false
        // Wrong action must not burn the ticket; only a matching consume or expiry removes it.
        if (ticket.action != action) return false
        tickets.remove(nonce)
        return clock() <= ticket.expiresAt
    }

    /** Throws if there's no valid ticket for this run, so the guarded operation never runs. */
    fun require(action: TwinAction, nonce: String) {
        if (!consume(action, nonce)) {
            throw IllegalStateException("${action.label} wasn't approved for this run. Nothing was saved or changed.")
        }
    }

    @Synchronized fun clear() = tickets.clear()

    companion object {
        const val TTL_MILLIS = 10 * 60 * 1000L
        /** Process-wide tickets used by the activities. */
        val shared = TwinApprovals()
        /** The actions whose operations must consume a ticket. */
        val GATED: Set<TwinAction> = setOf(
            TwinAction.EXPORT,
            TwinAction.SAVE_RELEASE_ARCHIVE,
            TwinAction.SAVE_SHARE_CARDS,
            TwinAction.SEAL_CAPSULE,
            TwinAction.DELETE_CAPSULE,
        )

        private val rng = SecureRandom()
        fun randomNonce(): String {
            val bytes = ByteArray(16)
            rng.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
