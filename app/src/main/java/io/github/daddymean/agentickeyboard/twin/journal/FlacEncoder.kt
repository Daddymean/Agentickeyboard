package io.github.daddymean.agentickeyboard.twin.journal

import java.io.OutputStream

/**
 * KEYBOARD-025: a small, dependency-free **lossless FLAC** encoder for mono PCM, so voice
 * answers are stored as FLAC (48 kHz mono, 16- or 24-bit) without ever writing raw audio
 * to disk: frames stream straight into the encrypted writer.
 *
 * Each block of [BLOCK_SIZE] samples becomes one frame with one subframe: CONSTANT when
 * every sample is equal, otherwise the cheapest FIXED predictor (order 0–4) with
 * partitioned-Rice-2 residuals (one partition), or VERBATIM if that would be larger.
 * STREAMINFO is written up front with "unknown" total samples, frame sizes and MD5,
 * which the FLAC format allows for streams; every frame carries CRC-8 and CRC-16.
 */
class FlacEncoder(
    private val out: OutputStream,
    val sampleRate: Int = 48_000,
    val bitsPerSample: Int = 16
) {
    init {
        require(bitsPerSample == 16 || bitsPerSample == 24) { "16 or 24 bits only" }
        require(sampleRate in 1..65_535 || sampleRate in setOf(88_200, 96_000, 176_400, 192_000)) { "bad sample rate" }
        writeHeader()
    }

    private val block = IntArray(BLOCK_SIZE)
    private var filled = 0
    private var frameNumber = 0L
    var samplesWritten = 0L
        private set

    fun write(samples: IntArray, count: Int = samples.size) {
        var i = 0
        while (i < count) {
            val n = minOf(count - i, BLOCK_SIZE - filled)
            System.arraycopy(samples, i, block, filled, n)
            filled += n
            i += n
            if (filled == BLOCK_SIZE) flushBlock()
        }
    }

    /** Writes the last, possibly short, frame. The stream is complete afterwards. */
    fun finish() {
        if (filled > 0) flushBlock()
        out.flush()
    }

    private fun writeHeader() {
        val w = BitWriter()
        w.bytes("fLaC".toByteArray(Charsets.US_ASCII))
        w.bits(1, 1) // last metadata block
        w.bits(0, 7) // STREAMINFO
        w.bits(34, 24)
        w.bits(BLOCK_SIZE.toLong(), 16) // min block size
        w.bits(BLOCK_SIZE.toLong(), 16) // max block size
        w.bits(0, 24) // min frame size: unknown
        w.bits(0, 24) // max frame size: unknown
        w.bits(sampleRate.toLong(), 20)
        w.bits(0, 3) // channels - 1 (mono)
        w.bits((bitsPerSample - 1).toLong(), 5)
        w.bits(0, 36) // total samples: unknown
        repeat(16) { w.bits(0, 8) } // MD5: unknown
        out.write(w.toByteArray())
    }

    private fun flushBlock() {
        val n = filled
        val w = BitWriter()
        w.bits(0x3FFE, 14) // sync
        w.bits(0, 1)
        w.bits(0, 1) // fixed block size
        val sizeCode = if (n == BLOCK_SIZE) 0b1100 else 0b0111
        w.bits(sizeCode.toLong(), 4)
        w.bits(sampleRateCode().toLong(), 4)
        w.bits(0, 4) // mono
        w.bits((if (bitsPerSample == 16) 0b100 else 0b110).toLong(), 3)
        w.bits(0, 1)
        utf8(w, frameNumber)
        if (sizeCode == 0b0111) w.bits((n - 1).toLong(), 16)
        if (sampleRateCode() == 0b1101) w.bits(sampleRate.toLong(), 16)
        w.bits(Crc.crc8(w.toByteArray()).toLong(), 8)
        subframe(w, n)
        w.alignToByte()
        val frame = w.toByteArray()
        out.write(frame)
        val crc = Crc.crc16(frame)
        out.write(crc ushr 8)
        out.write(crc and 0xFF)
        frameNumber++
        samplesWritten += n
        filled = 0
    }

    private fun sampleRateCode(): Int = when (sampleRate) {
        88_200 -> 0b0001; 176_400 -> 0b0010; 192_000 -> 0b0011; 8_000 -> 0b0100; 16_000 -> 0b0101
        22_050 -> 0b0110; 24_000 -> 0b0111; 32_000 -> 0b1000; 44_100 -> 0b1001; 48_000 -> 0b1010
        96_000 -> 0b1011; else -> 0b1101 // 16-bit Hz at the end of the header
    }

    private fun subframe(w: BitWriter, n: Int) {
        val bps = bitsPerSample
        if ((1 until n).all { block[it] == block[0] }) {
            w.bits(0, 1); w.bits(0, 6); w.bits(0, 1)
            w.signed(block[0].toLong(), bps)
            return
        }
        var bestOrder = 0
        var bestBits = Long.MAX_VALUE
        var bestK = 0
        for (order in 0..minOf(4, n - 1)) {
            var sum = 0L
            for (i in order until n) sum += zigzag(residual(order, i))
            val count = (n - order).coerceAtLeast(1)
            val k = riceParam(sum, count)
            val bits = order.toLong() * bps + 6 + 4 + 5 + riceBits(order, n, k)
            if (bits < bestBits) { bestBits = bits; bestOrder = order; bestK = k }
        }
        if (bestBits >= n.toLong() * bps) {
            w.bits(0, 1); w.bits(0b000001, 6); w.bits(0, 1)
            for (i in 0 until n) w.signed(block[i].toLong(), bps)
            return
        }
        w.bits(0, 1); w.bits((0b001000 or bestOrder).toLong(), 6); w.bits(0, 1)
        for (i in 0 until bestOrder) w.signed(block[i].toLong(), bps)
        w.bits(0b01, 2) // partitioned Rice with 5-bit parameters
        w.bits(0, 4) // partition order 0
        w.bits(bestK.toLong(), 5)
        for (i in bestOrder until n) {
            val u = zigzag(residual(bestOrder, i))
            w.unary(u ushr bestK)
            if (bestK > 0) w.bits(u and ((1L shl bestK) - 1), bestK)
        }
    }

    private fun riceBits(order: Int, n: Int, k: Int): Long {
        var bits = 0L
        for (i in order until n) bits += (zigzag(residual(order, i)) ushr k) + 1 + k
        return bits
    }

    private fun residual(order: Int, i: Int): Long {
        val s = block
        return when (order) {
            0 -> s[i].toLong()
            1 -> s[i].toLong() - s[i - 1]
            2 -> s[i].toLong() - 2L * s[i - 1] + s[i - 2]
            3 -> s[i].toLong() - 3L * s[i - 1] + 3L * s[i - 2] - s[i - 3]
            else -> s[i].toLong() - 4L * s[i - 1] + 6L * s[i - 2] - 4L * s[i - 3] + s[i - 4]
        }
    }

    private fun zigzag(r: Long): Long = (r shl 1) xor (r shr 63)

    private fun riceParam(sum: Long, count: Int): Int {
        val mean = sum / count
        var k = 0
        while (k < 30 && (1L shl (k + 1)) <= mean) k++
        return k
    }

    private fun utf8(w: BitWriter, v: Long) {
        when {
            v < 0x80 -> w.bits(v, 8)
            v < 0x800 -> { w.bits(0xC0L or (v shr 6), 8); w.bits(0x80L or (v and 0x3F), 8) }
            v < 0x10000 -> { w.bits(0xE0L or (v shr 12), 8); w.bits(0x80L or ((v shr 6) and 0x3F), 8); w.bits(0x80L or (v and 0x3F), 8) }
            v < 0x200000 -> {
                w.bits(0xF0L or (v shr 18), 8); w.bits(0x80L or ((v shr 12) and 0x3F), 8)
                w.bits(0x80L or ((v shr 6) and 0x3F), 8); w.bits(0x80L or (v and 0x3F), 8)
            }
            else -> {
                w.bits(0xF8L or (v shr 24), 8); w.bits(0x80L or ((v shr 18) and 0x3F), 8); w.bits(0x80L or ((v shr 12) and 0x3F), 8)
                w.bits(0x80L or ((v shr 6) and 0x3F), 8); w.bits(0x80L or (v and 0x3F), 8)
            }
        }
    }

    companion object {
        const val BLOCK_SIZE = 4096
    }
}

internal class BitWriter {
    private var buf = ByteArray(8192)
    private var len = 0
    private var acc = 0L
    private var accBits = 0

    fun bits(value: Long, count: Int) {
        var c = count
        while (c > 0) {
            val take = minOf(c, 32)
            val part = (value ushr (c - take)) and ((1L shl take) - 1)
            acc = (acc shl take) or part
            accBits += take
            c -= take
            while (accBits >= 8) {
                accBits -= 8
                put(((acc ushr accBits) and 0xFF).toInt())
            }
            acc = acc and ((1L shl accBits) - 1)
        }
    }

    fun signed(value: Long, count: Int) = bits(value and ((1L shl count) - 1), count)

    fun unary(zeros: Long) {
        var z = zeros
        while (z >= 32) { bits(0, 32); z -= 32 }
        bits(1, z.toInt() + 1)
    }

    fun bytes(b: ByteArray) = b.forEach { bits((it.toInt() and 0xFF).toLong(), 8) }

    fun alignToByte() { if (accBits > 0) bits(0, 8 - accBits) }

    fun toByteArray(): ByteArray {
        return buf.copyOf(len)
    }

    private fun put(b: Int) {
        if (len == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[len++] = b.toByte()
    }
}

internal object Crc {
    fun crc8(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc
    }

    fun crc16(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xFFFF else (crc shl 1) and 0xFFFF }
        }
        return crc
    }
}
