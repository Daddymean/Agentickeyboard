package io.github.daddymean.agentickeyboard.twin.journal

import io.github.daddymean.agentickeyboard.twin.TwinCipher
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * KEYBOARD-025: audio encrypted at rest with the twin's Keystore key scheme.
 *
 * The FLAC stream is cut into chunks of [CHUNK_BYTES]; each chunk is sealed with
 * AES-256-GCM through [TwinCipher] (a fresh IV per chunk) and bound by associated data
 * to the file id, its index and whether it is the last chunk
 * (`journal-audio-v1:<fileId>:<index>:<0|1>`). Reordered, swapped, dropped or truncated
 * chunks therefore fail to open. Plain audio is never written to disk.
 *
 * Layout: magic `AKJA`, version 1, then per chunk: IV length (u8), IV, ciphertext length
 * (i32), ciphertext, final flag (u8).
 */
class SealedAudioWriter(
    output: OutputStream,
    private val cipher: TwinCipher,
    private val fileId: String
) : OutputStream() {
    private val out = DataOutputStream(output)
    private val buffer = ByteArrayOutputStream(CHUNK_BYTES)
    private var index = 0
    private var closed = false

    init {
        out.write(MAGIC)
        out.writeByte(VERSION)
    }

    override fun write(b: Int) {
        buffer.write(b)
        if (buffer.size() >= CHUNK_BYTES) seal(final = false)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            val n = minOf(left, CHUNK_BYTES - buffer.size())
            buffer.write(b, o, n)
            o += n
            left -= n
            if (buffer.size() >= CHUNK_BYTES) seal(final = false)
        }
    }

    /** Seals the last chunk (possibly empty) and closes the file. */
    override fun close() {
        if (closed) return
        closed = true
        seal(final = true)
        out.close()
    }

    private fun seal(final: Boolean) {
        val sealed = cipher.seal(buffer.toByteArray(), aad(fileId, index, final))
        buffer.reset()
        out.writeByte(sealed.iv.size)
        out.write(sealed.iv)
        out.writeInt(sealed.ciphertext.size)
        out.write(sealed.ciphertext)
        out.writeByte(if (final) 1 else 0)
        index++
    }

    companion object {
        const val CHUNK_BYTES = 256 * 1024
        internal val MAGIC = "AKJA".toByteArray(Charsets.US_ASCII)
        internal const val VERSION = 1

        internal fun aad(fileId: String, index: Int, final: Boolean) =
            "journal-audio-v1:$fileId:$index:${if (final) 1 else 0}".toByteArray(Charsets.UTF_8)
    }
}

object SealedAudioReader {
    /** Opens a sealed recording; throws if anything is missing, reordered or tampered with. */
    fun readAll(input: InputStream, cipher: TwinCipher, fileId: String): ByteArray {
        val inp = DataInputStream(input)
        val magic = ByteArray(4)
        inp.readFully(magic)
        require(magic.contentEquals(SealedAudioWriter.MAGIC)) { "not a sealed recording" }
        require(inp.readUnsignedByte() == SealedAudioWriter.VERSION) { "unknown version" }
        val out = ByteArrayOutputStream()
        var index = 0
        while (true) {
            val ivLen = try { inp.readUnsignedByte() } catch (e: EOFException) { throw IllegalStateException("recording is truncated") }
            require(ivLen in 12..16) { "bad chunk" }
            val iv = ByteArray(ivLen).also(inp::readFully)
            val ctLen = inp.readInt()
            require(ctLen in 16..(SealedAudioWriter.CHUNK_BYTES + 64)) { "bad chunk" }
            val ct = ByteArray(ctLen).also(inp::readFully)
            val final = inp.readUnsignedByte() == 1
            out.write(cipher.open(TwinCipher.Sealed(iv, ct), SealedAudioWriter.aad(fileId, index, final)))
            index++
            if (final) {
                require(inp.read() == -1) { "data after the last chunk" }
                return out.toByteArray()
            }
        }
    }
}
