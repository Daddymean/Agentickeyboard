package io.github.daddymean.agentickeyboard.twin

/**
 * KEYBOARD-045 (approval gate 1a): single-use approval tickets for gated twin actions.
 *
 * The operation itself (writing an export, saving a release archive, sealing or
 * deleting a capsule) is blocked by default. It runs only if it can [consume] a
 * ticket that [TwinActionGate] issued after the policy allowed the action or Keith
 * tapped Allow. One ticket covers one action, once, and expires after [ttlMillis]
 * (long enough for the system file picker). Tickets live in memory only, so a
 * restart or process death revokes them. Pure logic with an injected clock.
 */
class TwinApprovals(private val clock: () -> Long = System::currentTimeMillis, private val ttlMillis: Long = TTL_MILLIS) {
    private val tickets = HashMap<TwinAction, Long>()

    /** Called only by the gate once the action is allowed. */
    @Synchronized fun issue(action: TwinAction) { tickets[action] = clock() + ttlMillis }

    /** True once per issued, unexpired ticket; false (blocked) otherwise. */
    @Synchronized fun consume(action: TwinAction): Boolean {
        val expires = tickets.remove(action) ?: return false
        return clock() <= expires
    }

    /** Throws if there's no valid ticket, so the guarded operation never runs. */
    fun require(action: TwinAction) {
        if (!consume(action)) throw IllegalStateException("${action.label} wasn't approved. Nothing was saved or changed.")
    }

    @Synchronized fun clear() = tickets.clear()

    companion object {
        const val TTL_MILLIS = 10 * 60 * 1000L
        /** Process-wide tickets used by the activities. */
        val shared = TwinApprovals()
        /** The actions whose operations must consume a ticket. */
        val GATED: Set<TwinAction> = setOf(TwinAction.EXPORT, TwinAction.SAVE_RELEASE_ARCHIVE, TwinAction.SAVE_SHARE_CARDS, TwinAction.SEAL_CAPSULE, TwinAction.DELETE_CAPSULE)
    }
}
