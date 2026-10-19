package io.github.daddymean.agentickeyboard.twin.memory

import android.content.Context
import io.github.daddymean.agentickeyboard.twin.TwinCipher
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * KEYBOARD-030: the legacy vault on disk, `noBackupFilesDir/twin/memory.sealed`, sealed
 * with the twin's Keystore key (AES-256-GCM, AAD `memory-v1`) like the dead-man state.
 * It is the only copy: the search index is rebuilt in memory from it ([MemoryIndex]).
 * Excluded from backup with the rest of `twin/`; delete-all removes it.
 */
class MemoryStore(private val file: File, private val cipher: TwinCipher) {
    @Synchronized
    fun load(): VaultState {
        if (!file.exists()) return VaultState()
        return DataInputStream(file.inputStream().buffered()).use { inp ->
            val iv = ByteArray(inp.readUnsignedByte()).also(inp::readFully)
            val ct = ByteArray(inp.readInt()).also(inp::readFully)
            val plain = cipher.open(TwinCipher.Sealed(iv, ct), AAD)
            try { LegacyVault.fromJson(JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) }
        }
    }

    @Synchronized
    fun save(state: VaultState) {
        val plain = LegacyVault.toJson(state).toString().toByteArray(Charsets.UTF_8)
        val sealed = try { cipher.seal(plain, AAD) } finally { plain.fill(0) }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeByte(sealed.iv.size); o.write(sealed.iv); o.writeInt(sealed.ciphertext.size); o.write(sealed.ciphertext)
        }
        check(tmp.renameTo(file)) { "could not save" }
    }

    /** Load, change, save, under one lock. */
    @Synchronized
    fun update(change: (VaultState) -> VaultState): VaultState = change(load()).also(::save)

    @Synchronized
    fun delete() {
        file.delete()
        File(file.path + ".tmp").delete()
    }

    companion object {
        private val AAD = "memory-v1".toByteArray(Charsets.US_ASCII)
        fun file(context: Context) = File(context.noBackupFilesDir, "twin/memory.sealed")
    }
}
