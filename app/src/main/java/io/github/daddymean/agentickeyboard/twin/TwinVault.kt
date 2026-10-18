package io.github.daddymean.agentickeyboard.twin

import android.content.Context
import io.github.daddymean.agentickeyboard.twin.deadman.DeadManStore
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore

/**
 * KEYBOARD-025: everything the legacy twin keeps on this phone, for delete-all.
 * The journal (answers, recordings, instrument results, settings) and the typed-message
 * store share one Keystore key, so delete-all removes both stores' files and then
 * destroys the key (crypto-shred).
 */
object TwinVault {
    @Volatile private var journal: JournalStore? = null

    fun journal(context: Context): JournalStore =
        journal ?: synchronized(this) {
            journal ?: JournalStore(context, JournalStore.dir(context), KeystoreTwinCipher()).also { journal = it }
        }

    @Volatile private var deadMan: DeadManStore? = null

    /** KEYBOARD-027: the dead-man state, sealed with the same Keystore key. */
    fun deadMan(context: Context): DeadManStore =
        deadMan ?: synchronized(this) {
            deadMan ?: DeadManStore(DeadManStore.file(context), KeystoreTwinCipher()).also { deadMan = it }
        }

    /** Deletes every journal record and recording, the dead-man state, every twin entry, and the key. Call on the twin worker. */
    fun deleteAll(context: Context) {
        deleteAll(journal(context), TwinLearning.store(context), deadMan(context))
    }

    internal fun deleteAll(journal: JournalStore, store: TwinStore, deadMan: DeadManStore? = null) {
        journal.deleteAll()
        deadMan?.delete()
        store.deleteAll() // also destroys the shared Keystore key
    }
}
