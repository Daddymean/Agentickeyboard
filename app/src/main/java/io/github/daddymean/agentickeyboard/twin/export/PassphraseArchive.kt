package io.github.daddymean.agentickeyboard.twin.export

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * KEYBOARD-026: the passphrase-encrypted twin export (`.aktx`), readable off the device
 * with `tools/twin-export/decrypt_twin_export.py`. Not bound to the Android Keystore,
 * on purpose: Keith decrypts it on his training machine with the passphrase alone.
 *
 * Format v1 (all integers big-endian):
 * ```
 * header  = "AKTX" | version u8 (1) | kdf u8 (1 = PBKDF2-HMAC-SHA256)
 *         | iterations u32 | salt (16) | nonce prefix (8)
 * chunk   = final u8 (0|1) | length u32 | AES-256-GCM ciphertext+tag
 * ```
 * Key = PBKDF2-HMAC-SHA256(passphrase as UTF-8, salt, iterations, 32 bytes).
 * Chunk i (from 0) holds up to [CHUNK_BYTES] of plaintext; its 12-byte IV is the nonce
 * prefix followed by i as u32, and its associated data is the whole header followed by
 * i as u32 and the final flag. A changed header, a wrong passphrase, or a tampered,
 * reordered, dropped or truncated chunk fails to open. Exactly one chunk is final and it
 * is the last thing in the file.
 */
object PassphraseArchive {
    val MAGIC = "AKTX".toByteArray(Charsets.US_ASCII)
    const val VERSION = 1
    const val KDF_PBKDF2_SHA256 = 1
    /** OWASP's 2023 recommendation for PBKDF2-HMAC-SHA256. */
    const val ITERATIONS = 600_000
    const val CHUNK_BYTES = 1 shl 20
    const val MIN_PASSPHRASE = 12
    private const val SALT = 16
    private const val PREFIX = 8
    private const val HEADER = 4 + 1 + 1 + 4 + SALT + PREFIX
    private const val TAG_BITS = 128

    internal fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        // PBEKeySpec encodes chars as UTF-8 for PBKDF2WithHmacSHA256 on Android and the JDK.
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES").also { bytes.fill(0) }
        } finally {
            spec.clearPassword()
        }
    }

    internal fun iv(prefix: ByteArray, index: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(index).array()

    internal fun aad(header: ByteArray, index: Int, final: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 5).put(header).putInt(index).put(if (final) 1 else 0).array()

    /** Streams plaintext into an encrypted archive; [close] writes the final chunk. */
    class Writer(
        output: OutputStream,
        passphrase: CharArray,
        iterations: Int = ITERATIONS,
        random: SecureRandom = SecureRandom()
    ) : OutputStream() {
        private val out = output
        private val prefix = ByteArray(PREFIX).also(random::nextBytes)
        private val header: ByteArray
        private val key: SecretKeySpec
        private val buffer = ByteArrayOutputStream(CHUNK_BYTES)
        private var index = 0
        private var closed = false

        init {
            require(passphrase.size >= MIN_PASSPHRASE) { "passphrase too short" }
            require(iterations >= 100_000) { "too few iterations" }
            val salt = ByteArray(SALT).also(random::nextBytes)
            header = ByteBuffer.allocate(HEADER).put(MAGIC).put(VERSION.toByte()).put(KDF_PBKDF2_SHA256.toByte())
                .putInt(iterations).put(salt).put(prefix).array()
            key = deriveKey(passphrase, salt, iterations)
            out.write(header)
        }

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var o = off
            var left = len
            while (left > 0) {
                val n = minOf(left, CHUNK_BYTES - buffer.size())
                buffer.write(b, o, n)
                o += n
                left -= n
                if (buffer.size() == CHUNK_BYTES) seal(final = false)
            }
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            seal(final = true)
            closed = true
            out.flush()
            out.close()
        }

        private fun seal(final: Boolean) {
            check(index != Int.MAX_VALUE) { "archive too large" }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv(prefix, index)))
            c.updateAAD(aad(header, index, final))
            val plain = buffer.toByteArray()
            val ct = c.doFinal(plain)
            plain.fill(0)
            buffer.reset()
            out.write(if (final) 1 else 0)
            out.write(ByteBuffer.allocate(4).putInt(ct.size).array())
            out.write(ct)
            index++
        }
    }

    /** Decrypts an archive as a stream; throws on any integrity failure, including truncation. */
    class Reader(input: InputStream, passphrase: CharArray) : InputStream() {
        private val inp = DataInputStream(input)
        private val header = ByteArray(HEADER)
        private val prefix: ByteArray
        private val key: SecretKeySpec
        private var current = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var done = false

        init {
            inp.readFully(header)
            val h = ByteBuffer.wrap(header)
            val magic = ByteArray(4).also { h.get(it) }
            require(magic.contentEquals(MAGIC)) { "not a twin export" }
            require(h.get().toInt() == VERSION) { "unsupported version" }
            require(h.get().toInt() == KDF_PBKDF2_SHA256) { "unsupported key derivation" }
            val iterations = h.int
            require(iterations in 100_000..10_000_000) { "bad iteration count" }
            val salt = ByteArray(SALT).also { h.get(it) }
            prefix = ByteArray(PREFIX).also { h.get(it) }
            key = deriveKey(passphrase, salt, iterations)
        }

        private fun next(): Boolean {
            if (done) return false
            val flag = try { inp.readUnsignedByte() } catch (e: EOFException) { throw IllegalStateException("archive is truncated") }
            require(flag == 0 || flag == 1) { "bad chunk" }
            val len = inp.readInt()
            require(len in 16..(CHUNK_BYTES + 16)) { "bad chunk" }
            val ct = ByteArray(len).also(inp::readFully)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv(prefix, index)))
            c.updateAAD(aad(header, index, flag == 1))
            current = try { c.doFinal(ct) } catch (e: Exception) {
                throw IllegalStateException("wrong passphrase or damaged archive", e)
            }
            pos = 0
            index++
            if (flag == 1) {
                require(inp.read() == -1) { "data after the last chunk" }
                done = true
            }
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= current.size) if (!next()) return -1
            val n = minOf(len, current.size - pos)
            System.arraycopy(current, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() = inp.close()
    }
}
