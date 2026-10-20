package io.github.daddymean.agentickeyboard.twin

import android.content.Context
import io.github.daddymean.agentickeyboard.twin.capsule.CapsuleStore
import io.github.daddymean.agentickeyboard.twin.consent.ConsentStore
import io.github.daddymean.agentickeyboard.twin.deadman.DeadManStore
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore
import io.github.daddymean.agentickeyboard.twin.memory.LegacyVault
import io.github.daddymean.agentickeyboard.twin.memory.MemoryStore

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

    @Volatile private var memory: MemoryStore? = null

    /** KEYBOARD-030: the approval-gated legacy vault and core blocks, sealed with the same key. */
    fun memory(context: Context): MemoryStore =
        memory ?: synchronized(this) {
            memory ?: MemoryStore(MemoryStore.file(context), KeystoreTwinCipher()).also { memory = it }
        }

    @Volatile private var consent: ConsentStore? = null

    /** KEYBOARD-033: each kid's opt-in, pause and retirement, sealed with the same key. */
    fun consent(context: Context): ConsentStore =
        consent ?: synchronized(this) {
            consent ?: ConsentStore(ConsentStore.file(context), KeystoreTwinCipher()).also { consent = it }
        }

    @Volatile private var capsules: CapsuleStore? = null

    /** KEYBOARD-036: Keith's milestone time capsules, each sealed with the same key. */
    fun capsules(context: Context): CapsuleStore =
        capsules ?: synchronized(this) {
            capsules ?: CapsuleStore(CapsuleStore.dir(context), KeystoreTwinCipher()).also { capsules = it }
        }

    /** Deletes one journal record, its recordings, and every memory derived from it. Call on the twin worker. */
    fun deleteJournalRecord(context: Context, id: Long): Boolean = deleteJournalRecord(journal(context), memory(context), id)

    internal fun deleteJournalRecord(journal: JournalStore, memory: MemoryStore, id: Long): Boolean {
        memory.update { LegacyVault.forgetRecord(it, id) }
        return journal.delete(id)
    }

    /** Deletes every journal record and recording, the memories, the consent record, the time capsules, the dead-man state, every twin entry, and the key. Call on the twin worker. */
    fun deleteAll(context: Context) {
        deleteAll(journal(context), TwinLearning.store(context), deadMan(context), memory(context), consent(context), capsules(context))
    }

    internal fun deleteAll(journal: JournalStore, store: TwinStore, deadMan: DeadManStore? = null, memory: MemoryStore? = null, consent: ConsentStore? = null, capsules: CapsuleStore? = null) {
        journal.deleteAll()
        capsules?.deleteAll()
        memory?.delete()
        consent?.delete()
        deadMan?.delete()
        store.deleteAll() // also destroys the shared Keystore key
    }
}
