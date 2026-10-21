package io.github.daddymean.agentickeyboard.twin.deadman

import org.json.JSONObject
import java.time.LocalDate

/**
 * KEYBOARD-039: the dead-man screen's state changes, one at a time and always applied to
 * the latest saved state. Every archive save gives a new data key, so a change computed
 * from an older snapshot would re-wrap the wrong key or roll the saved state back.
 * Call it on the twin worker: each method may read or write [store].
 */
class DeadManController(private val store: DeadManStore) {
    @Volatile
    var state: DeadManState? = null
        private set

    @Synchronized
    fun load(): DeadManState? = store.load().also { state = it }

    @Synchronized
    fun checkIn(today: LocalDate): DeadManState = update { DeadManSwitch.checkIn(it, today) }

    @Synchronized
    fun confirmIam(today: LocalDate): DeadManState = update { DeadManSwitch.confirmIam(it, today) }

    /** First set-up. The result is only shown; [confirmCards] saves it. */
    @Synchronized
    fun create(today: LocalDate): Rekey {
        check(state == null) { "already set up" }
        return DeadManSwitch.create(DeadManSwitch.GUARDIANS, today)
    }

    @Synchronized
    fun replaceHolder(x: Int, label: String, kidBirthDate: LocalDate?, today: LocalDate): Rekey =
        DeadManSwitch.replaceHolder(current(), x, label, kidBirthDate, today)

    @Synchronized
    fun reissue(today: LocalDate): Rekey = DeadManSwitch.reissue(current(), today)

    /**
     * Saves a new holder set once every card's check matches, and only if nothing was
     * saved since it was made (an archive, a check-in or another set). Otherwise its key
     * file would wrap an old data key or undo the newer change.
     */
    @Synchronized
    fun confirmCards(pending: Rekey, typedChecks: List<String>): DeadManState {
        require(DeadManSwitch.cardsConfirmed(pending, typedChecks)) { "A check doesn't match its card. Copy the card again and retype it." }
        check(madeFrom(pending, state)) { "Something was saved while these cards were shown, so nothing changed. Start again." }
        store.save(pending.state)
        state = pending.state
        return pending.state
    }

    /** The next archive's state: a fresh data key under the current set. Commit it with [commitArchive]. */
    @Synchronized
    fun prepareArchive(): DeadManState = DeadManSwitch.newArchive(current())

    /**
     * Records a written and verified archive as the latest one. A check-in or Google
     * confirmation made meanwhile is kept; a holder change made meanwhile wins, and the
     * archive must be saved again, because its key file belongs to the old set.
     */
    @Synchronized
    fun commitArchive(next: DeadManState): DeadManState {
        val cur = current()
        check(cur.epoch == next.epoch && cur.setId == next.setId) { "The key holders changed while the archive was being saved. Save it again." }
        val keyFile = JSONObject(cur.keyFile.toString())
            .put("archive_id", next.keyFile.getString("archive_id"))
            .put("wrap", next.keyFile.getJSONObject("wrap"))
        return cur.copy(dataKey = next.dataKey, archiveId = next.archiveId, keyFile = keyFile).also {
            store.save(it)
            state = it
        }
    }

    private fun current(): DeadManState = requireNotNull(state) { "the dead-man switch isn't set up" }

    private fun update(change: (DeadManState) -> DeadManState): DeadManState =
        change(current()).also { store.save(it); state = it }

    private fun madeFrom(pending: Rekey, cur: DeadManState?): Boolean =
        if (cur == null) pending.state.epoch == 1
        else pending.state.epoch == cur.epoch + 1 &&
            pending.state.archiveId.contentEquals(cur.archiveId) &&
            pending.state.dataKey.contentEquals(cur.dataKey) &&
            pending.state.lastCheckIn == cur.lastCheckIn
}
