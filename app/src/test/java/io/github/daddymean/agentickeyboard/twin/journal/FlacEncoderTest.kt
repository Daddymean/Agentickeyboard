package io.github.daddymean.agentickeyboard.twin.journal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * KEYBOARD-025: the FLAC encoder is lossless. A small reference decoder (written from
 * the format spec, RFC 9639) checks every frame's CRC-8/CRC-16 and that the samples
 * come back bit-exact; when ffmpeg is installed, it must decode the same samples too.
 */
class FlacEncoderTest {
    private fun encode(samples: IntArray, bits: Int, rate: Int = 48_000): ByteArray {
        val out = ByteArrayOutputStream()
        val enc = FlacEncoder(out, rate, bits)
        var o = 0
        val r = Random(3)
        while (o < samples.size) { val n = minOf(samples.size - o, r.nextInt(1, 9000)); enc.write(samples.copyOfRange(o, o + n), n); o += n }
        enc.finish()
        assertEquals(samples.size.toLong(), enc.samplesWritten)
        return out.toByteArray()
    }

    private fun signals(bits: Int): Map<String, IntArray> {
        val max = (1 shl (bits - 1)) - 1
        val min = -(1 shl (bits - 1))
        val r = Random(bits)
        return mapOf(
            "silence" to IntArray(10_000),
            "constant" to IntArray(5000) { 1234 },
            "sine" to IntArray(48_000) { (sin(2 * PI * 440 * it / 48_000.0) * max * 0.7).toInt() },
            "noise" to IntArray(20_000) { r.nextInt(min, max + 1) },
            "extremes" to IntArray(9000) { if (it % 2 == 0) max else min },
            "speechish" to IntArray(30_000) { (sin(it / 37.0) * sin(it / 3000.0) * max * 0.5 + r.nextInt(-200, 200)).toInt().coerceIn(min, max) },
            "short" to intArrayOf(5, -3, 7),
            "oneBlock" to IntArray(FlacEncoder.BLOCK_SIZE) { it - 2048 }
        )
    }

    @Test
    fun roundTripsBitExactAt16And24Bits() {
        for (bits in listOf(16, 24)) for ((name, s) in signals(bits)) {
            val flac = encode(s, bits)
            val decoded = MiniFlacDecoder(flac)
            assertEquals("$name/$bits rate", 48_000, decoded.sampleRate)
            assertEquals("$name/$bits bits", bits, decoded.bits)
            assertArrayEquals("$name/$bits", s, decoded.samples)
        }
    }

    @Test
    fun compressesSilenceAndSpeech() {
        assertTrue(encode(IntArray(48_000), 16).size < 1000)
        val s = signals(16).getValue("sine")
        assertTrue(encode(s, 16).size < s.size * 2 * 0.7)
    }

    @Test
    fun ffmpegDecodesTheSameSamples() {
        val ffmpeg = listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg").map(::File).firstOrNull { it.canExecute() }
        assumeTrue("ffmpeg not installed", ffmpeg != null)
        for (bits in listOf(16, 24)) {
            val s = signals(bits).getValue("speechish")
            val dir = kotlin.io.path.createTempDirectory("flac").toFile()
            try {
                val input = File(dir, "x.flac").apply { writeBytes(encode(s, bits)) }
                val pcm = File(dir, "x.raw")
                val p = ProcessBuilder(ffmpeg!!.path, "-v", "error", "-i", input.path, "-f", "s32le", "-acodec", "pcm_s32le", pcm.path)
                    .redirectErrorStream(true).start()
                val log = p.inputStream.bufferedReader().readText()
                assertEquals(log, 0, p.waitFor())
                val buf = ByteBuffer.wrap(pcm.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
                val got = IntArray(buf.remaining() / 4) { buf.int shr (32 - bits) }
                assertArrayEquals("ffmpeg/$bits", s, got)
            } finally {
                dir.deleteRecursively()
            }
        }
    }
}

/** Minimal mono FLAC decoder for the subset the encoder writes; verifies both CRCs. */
private class MiniFlacDecoder(private val data: ByteArray) {
    var sampleRate = 0
    var bits = 0
    val samples: IntArray

    private var pos = 0L // bit position

    private fun read(n: Int): Long {
        var v = 0L
        repeat(n) {
            val byte = data[(pos ushr 3).toInt()].toInt()
            v = (v shl 1) or ((byte ushr (7 - (pos and 7).toInt())) and 1).toLong()
            pos++
        }
        return v
    }

    private fun signed(n: Int): Long { val v = read(n); return if (v and (1L shl (n - 1)) != 0L) v - (1L shl n) else v }

    init {
        require(String(data, 0, 4, Charsets.US_ASCII) == "fLaC")
        pos = 32
        require(read(1) == 1L && read(7) == 0L && read(24) == 34L)
        val minBlock = read(16); val maxBlock = read(16)
        require(minBlock == 4096L && maxBlock == 4096L)
        read(24); read(24)
        sampleRate = read(20).toInt()
        require(read(3) == 0L)
        bits = read(5).toInt() + 1
        read(36); repeat(16) { read(8) }
        val out = ArrayList<Int>()
        var frame = 0L
        while (pos / 8 < data.size) {
            val start = (pos / 8).toInt()
            require(read(14) == 0x3FFEL && read(1) == 0L && read(1) == 0L)
            val sizeCode = read(4).toInt()
            require(read(4) == 0b1010L) // 48 kHz
            require(read(4) == 0L)
            require(read(3) == (if (bits == 16) 4L else 6L) && read(1) == 0L)
            val first = read(8)
            require(first < 0x80 || first >= 0xC0)
            var number = first
            var extra = 0
            if (first >= 0xC0) { extra = when { first >= 0xF8 -> 4; first >= 0xF0 -> 3; first >= 0xE0 -> 2; else -> 1 }; number = first and (0x3FL shr extra) }
            repeat(extra) { number = (number shl 6) or (read(8) and 0x3F) }
            require(number == frame) { "frame number" }
            val n = when (sizeCode) { 0b1100 -> 4096; 0b0111 -> read(16).toInt() + 1; else -> error("size code") }
            val headerEnd = (pos / 8).toInt()
            require(read(8).toInt() == Crc.crc8(data.copyOfRange(start, headerEnd))) { "crc8" }
            require(read(1) == 0L)
            val type = read(6).toInt()
            require(read(1) == 0L)
            val block = IntArray(n)
            when {
                type == 0 -> { val v = signed(bits).toInt(); block.fill(v) }
                type == 1 -> for (i in 0 until n) block[i] = signed(bits).toInt()
                type in 8..12 -> {
                    val order = type - 8
                    for (i in 0 until order) block[i] = signed(bits).toInt()
                    require(read(2) == 1L && read(4) == 0L)
                    val k = read(5).toInt()
                    require(k != 31)
                    for (i in order until n) {
                        var q = 0L
                        while (read(1) == 0L) q++
                        val u = (q shl k) or (if (k > 0) read(k) else 0L)
                        val res = (u ushr 1) xor -(u and 1)
                        val pred = when (order) {
                            0 -> 0L
                            1 -> block[i - 1].toLong()
                            2 -> 2L * block[i - 1] - block[i - 2]
                            3 -> 3L * block[i - 1] - 3L * block[i - 2] + block[i - 3]
                            else -> 4L * block[i - 1] - 6L * block[i - 2] + 4L * block[i - 3] - block[i - 4]
                        }
                        block[i] = (pred + res).toInt()
                    }
                }
                else -> error("subframe type $type")
            }
            if (pos % 8 != 0L) { val pad = 8 - (pos % 8).toInt(); require(read(pad) == 0L) }
            val end = (pos / 8).toInt()
            require(read(16).toInt() == Crc.crc16(data.copyOfRange(start, end))) { "crc16" }
            block.forEach { out += it }
            frame++
        }
        samples = out.toIntArray()
    }
}
