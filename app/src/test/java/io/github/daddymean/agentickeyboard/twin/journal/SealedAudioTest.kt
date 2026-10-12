package io.github.daddymean.agentickeyboard.twin.journal

import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.random.Random

class SealedAudioTest {
    private val cipher = SoftwareTwinCipher()
    private val id = "0f8fad5b-d9cb-469f-a165-70867728950e"

    private fun seal(data: ByteArray, fileId: String = id): ByteArray {
        val out = ByteArrayOutputStream()
        SealedAudioWriter(out, cipher, fileId).use { w ->
            var o = 0
            val r = Random(1)
            while (o < data.size) { val n = minOf(data.size - o, r.nextInt(1, 70_000)); w.write(data, o, n); o += n }
        }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, fileId: String = id) = SealedAudioReader.readAll(sealed.inputStream(), cipher, fileId)

    private fun fails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    private class Chunk(val iv: ByteArray, val ct: ByteArray, val final: Int)

    private fun chunks(sealed: ByteArray): List<Chunk> {
        val inp = DataInputStream(sealed.inputStream())
        inp.skipBytes(5)
        val out = ArrayList<Chunk>()
        while (inp.available() > 0) {
            val iv = ByteArray(inp.readUnsignedByte()).also(inp::readFully)
            val ct = ByteArray(inp.readInt()).also(inp::readFully)
            out += Chunk(iv, ct, inp.readUnsignedByte())
        }
        return out
    }

    private fun assemble(list: List<Chunk>): ByteArray {
        val bo = ByteArrayOutputStream()
        DataOutputStream(bo).apply {
            write("AKJA".toByteArray()); writeByte(1)
            for (c in list) { writeByte(c.iv.size); write(c.iv); writeInt(c.ct.size); write(c.ct); writeByte(c.final) }
        }
        return bo.toByteArray()
    }

    @Test
    fun roundTripsAcrossChunksAndHidesPlaintext() {
        for (size in listOf(0, 1, SealedAudioWriter.CHUNK_BYTES, SealedAudioWriter.CHUNK_BYTES + 1, 700_000)) {
            val data = ByteArray(size) { (it % 251).toByte() }
            if (size > 4) "fLaC".toByteArray().copyInto(data)
            val sealed = seal(data)
            assertArrayEquals(data, open(sealed))
            if (size > 4) assertFalse(String(sealed, Charsets.ISO_8859_1).contains("fLaC"))
        }
    }

    @Test
    fun tamperingReorderingTruncationAndWrongFileFail() {
        val data = Random(7).nextBytes(SealedAudioWriter.CHUNK_BYTES * 2 + 1000)
        val sealed = seal(data)
        val cs = chunks(sealed)
        assertTrue(cs.size == 3 && cs.last().final == 1)
        // Bit flip.
        fails { open(sealed.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }) }
        // Reordered chunks.
        fails { open(assemble(listOf(cs[1], cs[0], cs[2]))) }
        // Dropped final chunk, with and without forging the final flag.
        fails { open(assemble(cs.take(2))) }
        fails { open(assemble(listOf(cs[0], Chunk(cs[1].iv, cs[1].ct, 1)))) }
        // Dropped middle chunk.
        fails { open(assemble(listOf(cs[0], cs[2]))) }
        // Truncated bytes and trailing garbage.
        fails { open(sealed.copyOf(sealed.size - 10)) }
        fails { open(sealed + byteArrayOf(0)) }
        // Moved to another recording's name.
        fails { open(sealed, "1f8fad5b-d9cb-469f-a165-70867728950e") }
        // Without the key.
        cipher.destroyKey()
        fails { open(sealed) }
    }
}
