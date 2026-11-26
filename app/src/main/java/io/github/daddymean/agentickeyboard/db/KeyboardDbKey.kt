package io.github.daddymean.agentickeyboard.db

import io.github.daddymean.agentickeyboard.twin.KeystoreTwinCipher
import io.github.daddymean.agentickeyboard.twin.TwinCipher
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom

/** Wraps the keyboard database key; [KeystoreDbKeyWrapper] in the app, a fake in tests. */
interface DbKeyWrapper {
    fun wrap(raw: ByteArray): TwinCipher.Sealed

    /** Throws when the wrapping key is gone or the blob was tampered with. */
    fun unwrap(sealed: TwinCipher.Sealed): ByteArray

    /** False only when the Keystore definitely has no wrapping key (a lost key, not a hiccup). */
    fun keyExists(): Boolean
}

/**
 * The twin's mechanism, reused as is: [KeystoreTwinCipher] (AES-256-GCM, key generated in the
 * Android Keystore, never exported or backed up) under its own alias, so wiping the twin never
 * touches the keyboard database and the other way round.
 */
class KeystoreDbKeyWrapper(private val alias: String = KEY_ALIAS) : DbKeyWrapper {
    private val cipher = KeystoreTwinCipher(alias)

    override fun wrap(raw: ByteArray) = cipher.seal(raw, AAD)
    override fun unwrap(sealed: TwinCipher.Sealed) = cipher.open(sealed, AAD)
    override fun keyExists(): Boolean = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(alias)

    companion object {
        const val KEY_ALIAS = "agentic_keyboard_db_key_v1"
        private val AAD = "kbdb-key-v1".toByteArray(Charsets.UTF_8)
    }
}

/**
 * KEYBOARD-048: the keyboard database's 256-bit key. It is random (not derived from anything),
 * stored only wrapped by [DbKeyWrapper] in `noBackupFilesDir`, and handed to SQLCipher as a
 * raw key (`x'…'`), so opening skips SQLCipher's PBKDF2 and costs no extra startup time.
 *
 * The wrapped file is written to a temp file, synced and renamed, so a crash leaves either the
 * old file or the new one, never half of one. A new key is only ever created when no encrypted
 * database exists yet (see [KeyboardDbMigrator]).
 */
class KeyboardDbKey(internal val file: File, private val wrapper: DbKeyWrapper, private val random: SecureRandom = SecureRandom()) {

    sealed interface Read {
        class Ok(val sqlKey: ByteArray) : Read
        /** No key file, or the Keystore no longer has the wrapping key: the key can never come back. */
        data object Lost : Read
        /** The key file and Keystore key both exist but unwrapping failed; may work next launch. */
        data object Unavailable : Read
    }

    fun exists(): Boolean = file.isFile

    fun read(): Read {
        if (!file.isFile) return Read.Lost
        // A file that doesn't parse, or a blob the Keystore key rejects (bad GCM tag), can never open.
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return Read.Unavailable
        val sealed = runCatching { decode(bytes) }.getOrNull() ?: return Read.Lost
        repeat(ATTEMPTS) { attempt ->
            val raw = try {
                wrapper.unwrap(sealed)
            } catch (e: javax.crypto.AEADBadTagException) {
                return Read.Lost
            } catch (e: android.security.keystore.KeyPermanentlyInvalidatedException) {
                // Only a permanent invalidation is Lost, and only when the alias
                // check can be made. Any other InvalidKeyException (including
                // UserNotAuthenticatedException and "Keystore operation failed")
                // is a transient Keystore error.
                if (runCatching { wrapper.keyExists() }.isFailure) null
                else return Read.Lost
            } catch (e: java.security.InvalidKeyException) {
                null
            } catch (e: Exception) {
                null
            }
            if (raw != null) {
                if (raw.size != KEY_BYTES) return Read.Lost
                return Read.Ok(sqlKey(raw).also { raw.fill(0) })
            }
            if (runCatching { wrapper.keyExists() }.getOrNull() == false) return Read.Lost
            if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_MS)
        }
        return Read.Unavailable
    }

    /** Renames the wrapped-key file to `*.lost-<stamp>` so [create] never overwrites it. */
    fun moveAside(stamp: Long): File? {
        if (!file.isFile) return null
        val dest = File("${file.path}.lost-$stamp")
        if (!file.renameTo(dest)) return null
        syncDir(file.parentFile)
        return dest
    }

    /**
     * Copies the wrapped-key file to `*.lost-<stamp>` and leaves the live file in place, so a
     * database aside can be kept as a pair with the same stamp without abandoning the current key.
     */
    fun copyAside(stamp: Long): File? {
        if (!file.isFile) return null
        val dest = File("${file.path}.lost-$stamp")
        if (dest.exists()) return dest
        file.copyTo(dest, overwrite = false)
        syncDir(file.parentFile)
        return dest
    }

    /** Unwrap an aside of this key (same wrapping key, different file). */
    internal fun readAside(aside: File): Read = KeyboardDbKey(aside, wrapper).read()

    /** Makes and stores a new key. Refuses to overwrite an existing wrapped-key file. */
    fun create(): ByteArray {
        check(!file.isFile) { "won't overwrite an existing key" }
        val raw = ByteArray(KEY_BYTES).also(random::nextBytes)
        try {
            val blob = encode(wrapper.wrap(raw))
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use { out -> out.write(blob); out.fd.sync() }
            if (!tmp.renameTo(file)) error("Couldn't store the keyboard database key")
            syncDir(file.parentFile)
            return sqlKey(raw)
        } finally {
            raw.fill(0)
        }
    }

    companion object {
        const val KEY_BYTES = 32
        private const val VERSION = 1
        private const val ATTEMPTS = 3
        private const val RETRY_MS = 50L

        /** Production key file: never backed up, never transferred. */
        fun keyFile(noBackupDir: File) = File(noBackupDir, "keyboard_db/agentic_keyboard_database.key")

        /** SQLCipher raw-key syntax: `x'<64 hex digits>'` as bytes. */
        fun sqlKey(raw: ByteArray): ByteArray {
            val out = ByteArray(raw.size * 2 + 3)
            out[0] = 'x'.code.toByte(); out[1] = '\''.code.toByte(); out[out.size - 1] = '\''.code.toByte()
            raw.forEachIndexed { i, b ->
                val v = b.toInt() and 0xff
                out[2 + i * 2] = HEX[v ushr 4]
                out[3 + i * 2] = HEX[v and 0xf]
            }
            return out
        }

        private val HEX = "0123456789abcdef".toByteArray(Charsets.US_ASCII)

        private fun encode(s: TwinCipher.Sealed): ByteArray {
            val bytes = java.io.ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeByte(VERSION)
                out.writeShort(s.iv.size); out.write(s.iv)
                out.writeShort(s.ciphertext.size); out.write(s.ciphertext)
            }
            return bytes.toByteArray()
        }

        private fun decode(bytes: ByteArray): TwinCipher.Sealed = DataInputStream(bytes.inputStream()).use { input ->
            require(input.readUnsignedByte() == VERSION)
            val iv = ByteArray(input.readUnsignedShort()).also(input::readFully)
            val body = ByteArray(input.readUnsignedShort()).also(input::readFully)
            TwinCipher.Sealed(iv, body)
        }
    }
}

/** fsync a directory so a rename in it survives power loss. Best effort (not every FS allows it). */
internal fun syncDir(dir: File?) {
    if (dir == null) return
    runCatching {
        java.nio.channels.FileChannel.open(dir.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    }
}
