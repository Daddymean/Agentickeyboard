package io.github.daddymean.agentickeyboard.twin.journal

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.daddymean.agentickeyboard.util.SafeLog
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/**
 * KEYBOARD-025: records one voice answer as lossless FLAC, 48 kHz mono, straight into an
 * encrypted file. 24-bit when the device supports it, otherwise 16-bit. The unprocessed
 * microphone source is used where available (no noise suppression or auto-gain, as the
 * voice scripts ask), falling back to the voice-recognition source.
 *
 * Only runs while the journal screen is open and Keith has pressed Record; the screen
 * stops it when it leaves the foreground. Requires RECORD_AUDIO, which the screen asks
 * for on the first Record tap.
 */
class JournalRecorder(private val context: Context, private val store: JournalStore) {
    /** Called on the recording thread with the peak level of each block, in dBFS. */
    var onLevel: ((Double) -> Unit)? = null

    @Volatile private var running = false
    private var thread: Thread? = null
    private var fileId: String? = null
    private var startedAt = 0L
    private var error: Throwable? = null

    val isRecording: Boolean get() = running

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        check(!running) { "already recording" }
        check(hasPermission()) { "microphone permission not granted" }
        // A take whose thread failed on its own is not stopped by the screen; clear it up.
        thread?.join()
        thread = null
        fileId?.let { if (error != null) store.deleteAudio(it) }
        fileId = null
        val (record, bits) = open()
        val (id, sealed) = try { store.newRecording() } catch (t: Throwable) { record.release(); throw t }
        fileId = id
        error = null
        running = true
        startedAt = System.currentTimeMillis()
        thread = Thread({
            try {
                sealed.use { out ->
                    val encoder = FlacEncoder(out, SAMPLE_RATE, bits)
                    val bytesPerSample = bits / 8
                    val buf = ByteArray(SAMPLE_RATE / 10 * bytesPerSample)
                    val samples = IntArray(buf.size / bytesPerSample)
                    record.startRecording()
                    while (running) {
                        val n = record.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        val count = n / bytesPerSample
                        var peak = 0
                        for (i in 0 until count) {
                            val o = i * bytesPerSample
                            val s = if (bits == 16) {
                                (buf[o].toInt() and 0xFF) or (buf[o + 1].toInt() shl 8)
                            } else {
                                (buf[o].toInt() and 0xFF) or ((buf[o + 1].toInt() and 0xFF) shl 8) or (buf[o + 2].toInt() shl 16)
                            }
                            samples[i] = s
                            peak = max(peak, abs(s))
                        }
                        encoder.write(samples, count)
                        val full = (1 shl (bits - 1)).toDouble()
                        onLevel?.invoke(if (peak == 0) -120.0 else 20 * log10(peak / full))
                    }
                    encoder.finish()
                }
            } catch (t: Throwable) {
                error = t
                SafeLog.w(TAG, "Recording failed", t)
            } finally {
                running = false
                runCatching { record.stop() }
                record.release()
            }
        }, "journal-recorder").apply { start() }
    }

    /** Stops and returns the sealed recording's id and length in milliseconds. */
    fun stop(): Pair<String, Long> {
        running = false
        thread?.join()
        thread = null
        val id = fileId ?: error("not recording")
        fileId = null
        error?.let { store.deleteAudio(id); throw IllegalStateException("Recording failed", it) }
        return id to (System.currentTimeMillis() - startedAt)
    }

    @SuppressLint("MissingPermission")
    private fun open(): Pair<AudioRecord, Int> {
        val am = context.getSystemService(AudioManager::class.java)
        val unprocessed = am?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        for ((encoding, bits) in listOf(AudioFormat.ENCODING_PCM_24BIT_PACKED to 24, AudioFormat.ENCODING_PCM_16BIT to 16)) {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
            if (min <= 0) continue
            val record = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(SAMPLE_RATE).setEncoding(encoding)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                    )
                    .setBufferSizeInBytes(max(min * 4, SAMPLE_RATE * bits / 8))
                    .build()
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record to bits
            record.release()
        }
        error("No 48 kHz microphone input is available")
    }

    companion object {
        private const val TAG = "JournalRecorder"
        const val SAMPLE_RATE = 48_000
    }
}
