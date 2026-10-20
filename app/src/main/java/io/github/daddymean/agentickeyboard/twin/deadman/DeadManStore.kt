package io.github.daddymean.agentickeyboard.twin.deadman

import android.content.Context
import io.github.daddymean.agentickeyboard.twin.TwinCipher
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * KEYBOARD-027: stores [DeadManState] in `noBackupFilesDir/twin/deadman.sealed`, sealed
 * with the twin's Keystore key (AES-256-GCM, AAD `deadman-v1`). Excluded from backup like
 * the rest of the twin; delete-all removes it and destroys the key.
 */
class DeadManStore(private val file: File, private val cipher: TwinCipher) {
    @Synchronized
    fun load(): DeadManState? {
        if (!file.exists()) return null
        return DataInputStream(file.inputStream().buffered()).use { inp ->
            val iv = ByteArray(inp.readUnsignedByte()).also(inp::readFully)
            val ct = ByteArray(inp.readInt()).also(inp::readFully)
            val plain = cipher.open(TwinCipher.Sealed(iv, ct), AAD)
            try { DeadManState.fromJson(JSONObject(String(plain, Charsets.UTF_8))) } finally { plain.fill(0) }
        }
    }

    @Synchronized
    fun save(state: DeadManState) {
        val plain = state.toJson().toString().toByteArray(Charsets.UTF_8)
        val sealed = try { cipher.seal(plain, AAD) } finally { plain.fill(0) }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.writeByte(sealed.iv.size); o.write(sealed.iv); o.writeInt(sealed.ciphertext.size); o.write(sealed.ciphertext)
        }
        check(tmp.renameTo(file)) { "could not save" }
    }

    @Synchronized
    fun delete() {
        file.delete()
        File(file.path + ".tmp").delete()
    }

    companion object {
        private val AAD = "deadman-v1".toByteArray(Charsets.US_ASCII)
        fun file(context: Context) = File(context.noBackupFilesDir, "twin/deadman.sealed")
    }
}
