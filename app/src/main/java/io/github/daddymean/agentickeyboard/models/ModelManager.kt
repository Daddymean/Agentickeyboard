package io.github.daddymean.agentickeyboard.models

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Result of [ModelManager.download]. The file is never kept when the hash does not match. */
sealed class DownloadResult {
    data class Ready(val file: File) : DownloadResult()
    data object NotOptedIn : DownloadResult()
    data class HashMismatch(val actualSha256: String) : DownloadResult()
    data class Failed(val message: String, val cause: Throwable? = null) : DownloadResult()
}

/**
 * Fetches bytes for a model URL into [dest]. Injected so unit tests never touch the network.
 * The production implementation may use OkHttp; this interface itself has no network types.
 * A plain fetcher always writes the whole file from byte 0 (no resume).
 */
fun interface ModelBytesFetcher {
    fun fetch(url: String, dest: File)
}

/**
 * A fetcher that can continue a partial file. [ModelManager] keeps the `.part` file after a
 * network failure and calls this again, so a dropped connection costs only the bytes that
 * were not yet written, not the whole download.
 */
interface ResumableModelFetcher : ModelBytesFetcher {
    /**
     * Appends the rest of [url] to [dest]. If [dest] already holds N bytes, asks the server
     * for bytes N onward (HTTP Range). If the server ignores the range and sends the whole
     * body, [dest] is truncated and rewritten. Throws [ModelResumeRejectedException] when the
     * answer cannot be appended safely (the caller then discards the partial). Whatever was
     * written before a failure stays in [dest]. [onProgress] gets the total bytes now in [dest].
     */
    fun fetchResumable(url: String, dest: File, onProgress: (Long) -> Unit)

    override fun fetch(url: String, dest: File) {
        dest.delete()
        fetchResumable(url, dest) { }
    }
}

/** The server answered with an HTTP error status. Only transient statuses are worth retrying. */
class ModelHttpException(val code: Int) : IOException("download failed: HTTP $code") {
    val retryable: Boolean get() = code == 408 || code == 429 || code >= 500
}

/** The server's answer to a resume request cannot be appended to the partial file. */
class ModelResumeRejectedException(message: String) : IOException(message)

/**
 * Streaming HTTP fetcher with HTTP Range resume. The read timeout is 60 s: a stalled
 * connection is detected quickly and the manager resumes from the bytes already on disk,
 * instead of waiting minutes and then restarting a multi-gigabyte file from zero.
 */
class OkHttpModelFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) : ResumableModelFetcher {
    override fun fetchResumable(url: String, dest: File, onProgress: (Long) -> Unit) {
        dest.parentFile?.mkdirs()
        val have = if (dest.isFile) dest.length() else 0L
        val builder = Request.Builder().url(url).get()
            // Never let a transparent gzip layer change what a byte offset means.
            .header("Accept-Encoding", "identity")
        if (have > 0) builder.header("Range", "bytes=$have-")
        client.newCall(builder.build()).execute().use { response ->
            val append = when (response.code) {
                206 -> {
                    val start = contentRangeStart(response.header("Content-Range"))
                    if (start != have) {
                        throw ModelResumeRejectedException("server answered a different range ($start, wanted $have)")
                    }
                    have > 0
                }
                200 -> false
                416 -> throw ModelResumeRejectedException("server rejected the range")
                else -> throw ModelHttpException(response.code)
            }
            val body = response.body
            var written = if (append) have else 0L
            var lastReported = written
            var lastReportNanos = System.nanoTime()
            onProgress(written)
            FileOutputStream(dest, append).use { fileOut ->
                // OkHttp hands out at most one 8 KB segment per read; batch the writes.
                val out = BufferedOutputStream(fileOut, WRITE_BUFFER_BYTES)
                val input = body.byteStream()
                val buf = ByteArray(COPY_BUFFER_BYTES)
                try {
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("download cancelled")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        val now = System.nanoTime()
                        if (written - lastReported >= PROGRESS_BYTES || now - lastReportNanos >= PROGRESS_NANOS) {
                            onProgress(written)
                            lastReported = written
                            lastReportNanos = now
                        }
                    }
                } finally {
                    // Keep every byte received so far, so a retry resumes from the real end.
                    try { out.flush() } catch (_: IOException) { }
                }
            }
            onProgress(written)
        }
    }

    companion object {
        const val COPY_BUFFER_BYTES = 256 * 1024
        const val WRITE_BUFFER_BYTES = 256 * 1024
        private const val PROGRESS_BYTES = 1L * 1024 * 1024
        private const val PROGRESS_NANOS = 250_000_000L

        /** First byte of a `Content-Range: bytes 100-199/200` header, or -1 when absent or unparseable. */
        fun contentRangeStart(header: String?): Long {
            val m = CONTENT_RANGE.matchEntire(header?.trim().orEmpty()) ?: return -1L
            return m.groupValues[1].toLongOrNull() ?: -1L
        }

        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-\d+/(?:\d+|\*)""", RegexOption.IGNORE_CASE)
    }
}

/**
 * Per-model opt-in, off by default, stored in a private prefs file (not the twin
 * message store). Revoking opt-in deletes that model's files.
 */
class ModelConsent(context: Context, prefsName: String = PREFS) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun isOptedIn(id: String): Boolean = prefs.getBoolean(key(id), false)

    fun setOptedIn(id: String, optedIn: Boolean) {
        prefs.edit().putBoolean(key(id), optedIn).apply()
    }

    companion object {
        const val PREFS = "model_manager"
        fun key(id: String) = "opt_in.$id"
    }
}

/**
 * Generic opt-in, hash-pinned on-device model manager.
 *
 * API for reuse (Gemma 4 E2B rewrite, or any later model):
 * - Construct with a writable [rootDir] (production: [dir]), [ModelConsent], and a
 *   [ModelBytesFetcher] (production: [OkHttpModelFetcher]).
 * - [isOptedIn] / [setOptedIn] gate every download. Default is off. Setting
 *   opted-in to false deletes that model's directory, including any partial download.
 * - [download] returns [DownloadResult.NotOptedIn] and writes nothing when the
 *   user has not opted in. It streams to a `.part` file. With a [ResumableModelFetcher]
 *   a network failure keeps the `.part` file and is retried a few times (resuming with
 *   HTTP Range), and a later [download] call continues from the same bytes. The file
 *   is checked against [ModelSpec.sha256] and only then renamed into place. A mismatch
 *   after a resumed download is retried once from byte 0 (a bad partial must never be
 *   the final word); a mismatch on a fresh full download deletes the temp and any
 *   previous dest file for that spec.
 * - [verifiedFile] returns the on-disk file only when it exists and matches the
 *   pin; a stale or tampered file is deleted and reported as absent. A positive result
 *   is remembered in memory for the process (keyed by path, size, modified time and
 *   pin), so repeated checks do not re-hash a multi-gigabyte file.
 * - [delete] removes the model files without changing the opt-in flag.
 *
 * No twin memory or personal data is read or uploaded. The only network use is
 * the opt-in download of the pinned URL.
 */
class ModelManager(
    private val rootDir: File,
    private val consent: ModelConsent,
    private val fetcher: ModelBytesFetcher,
    private val freeSpace: (File) -> Long = { it.usableSpace },
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
    private val retryDelaysMillis: List<Long> = DEFAULT_RETRY_DELAYS_MILLIS
) {
    fun isOptedIn(id: String): Boolean = consent.isOptedIn(id)

    fun setOptedIn(spec: ModelSpec, optedIn: Boolean) {
        consent.setOptedIn(spec.id, optedIn)
        if (!optedIn) delete(spec)
    }

    fun fileFor(spec: ModelSpec): File = File(File(rootDir, spec.id), spec.fileName)

    private fun partFor(spec: ModelSpec): File = File(fileFor(spec).path + PART_SUFFIX)
    private fun partMetaFor(spec: ModelSpec): File = File(fileFor(spec).path + PART_SUFFIX + ".meta")

    /** The partial download is only trusted when it was started for this exact pin. */
    private fun partKey(spec: ModelSpec): String = "${spec.downloadUrl}\n${spec.sha256.lowercase()}\n${spec.sizeBytes}"

    /** Bytes of a resumable partial download kept for [spec], or 0. Cheap: no hashing. */
    fun partialBytes(spec: ModelSpec): Long {
        val part = partFor(spec)
        val meta = partMetaFor(spec)
        if (!part.isFile || !meta.isFile) return 0L
        if (runCatching { meta.readText() }.getOrNull() != partKey(spec)) return 0L
        return part.length().takeIf { it in 1 until spec.sizeBytes } ?: 0L
    }

    fun verifiedFile(spec: ModelSpec): File? {
        val file = fileFor(spec)
        if (!file.isFile) return null
        val stamp = Stamp(spec.sha256.lowercase(), file.length(), file.lastModified())
        if (verifiedCache[file.absolutePath] == stamp) return file
        val actual = sha256(file)
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            verifiedCache.remove(file.absolutePath)
            file.delete()
            File(file.path + ".tmp").delete()
            return null
        }
        verifiedCache[file.absolutePath] = stamp
        return file
    }

    fun isReady(spec: ModelSpec): Boolean = verifiedFile(spec) != null

    /**
     * Downloads and verifies [spec]. [onProgress] receives (bytes on disk, total bytes) from the
     * calling thread; when bytes reach the total, hash verification is about to start. A resumed
     * download first reports the bytes it is continuing from.
     */
    fun download(spec: ModelSpec, onProgress: (bytes: Long, total: Long) -> Unit = { _, _ -> }): DownloadResult {
        if (!consent.isOptedIn(spec.id)) return DownloadResult.NotOptedIn
        val dest = fileFor(spec)
        val part = partFor(spec)
        val meta = partMetaFor(spec)
        dest.parentFile?.mkdirs()
        File(dest.path + ".tmp").delete() // left by older builds
        val resumable = fetcher as? ResumableModelFetcher
        val total = spec.sizeBytes

        fun discardPart() {
            part.delete()
            meta.delete()
        }
        try {
            if (resumable == null || !meta.isFile || runCatching { meta.readText() }.getOrNull() != partKey(spec)) {
                discardPart()
            }
            if (part.length() > total) discardPart()
            val missing = total - part.length()
            if (missing > 0) {
                val free = freeSpace(dest.parentFile ?: rootDir)
                if (free < missing) {
                    return DownloadResult.Failed(
                        "Not enough free storage: ${missing / MIB} MB more needed, ${free / MIB} MB free."
                    )
                }
            }
            if (!part.exists()) {
                meta.writeText(partKey(spec))
            }

            var usedResume = false
            var failures = 0
            var attempts = 0
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    return DownloadResult.Failed("Download cancelled; the partial file is kept so it can resume.")
                }
                val before = part.length()
                try {
                    if (before < total) {
                        if (before > 0 && resumable != null) usedResume = true
                        onProgress(before, total)
                        if (resumable != null) {
                            resumable.fetchResumable(spec.downloadUrl, part) { onProgress(it, total) }
                        } else {
                            part.delete()
                            fetcher.fetch(spec.downloadUrl, part)
                        }
                    }
                    val length = part.length()
                    if (length > total) {
                        discardPart()
                        throw ModelResumeRejectedException("downloaded file is larger than its pinned size")
                    }
                    if (length < total) throw IOException("download ended early ($length of $total bytes)")
                    onProgress(total, total)
                    val actual = sha256(part)
                    if (!actual.equals(spec.sha256, ignoreCase = true)) {
                        discardPart()
                        if (usedResume) {
                            // The resumed bytes may be the problem: start once from byte 0.
                            usedResume = false
                            meta.writeText(partKey(spec))
                            continue
                        }
                        if (dest.exists()) dest.delete()
                        verifiedCache.remove(dest.absolutePath)
                        return DownloadResult.HashMismatch(actual)
                    }
                    if (dest.exists() && !dest.delete()) {
                        discardPart()
                        return DownloadResult.Failed("could not replace existing model file")
                    }
                    if (!part.renameTo(dest)) {
                        discardPart()
                        return DownloadResult.Failed("could not move verified model file into place")
                    }
                    meta.delete()
                    verifiedCache[dest.absolutePath] =
                        Stamp(spec.sha256.lowercase(), dest.length(), dest.lastModified())
                    return DownloadResult.Ready(dest)
                } catch (e: ModelResumeRejectedException) {
                    // The server will not continue this partial: restart from byte 0.
                    discardPart()
                    meta.writeText(partKey(spec))
                    usedResume = false
                    attempts++
                    failures++
                    if (resumable == null || failures > retryDelaysMillis.size || attempts > MAX_ATTEMPTS) {
                        return DownloadResult.Failed(e.message ?: "resume rejected", e)
                    }
                } catch (e: IOException) {
                    if (part.length() > before) failures = 0 // made progress: the network is working
                    attempts++
                    failures++
                    val retry = resumable != null &&
                        (e as? ModelHttpException)?.retryable != false &&
                        e !is InterruptedIOException &&
                        failures <= retryDelaysMillis.size && attempts <= MAX_ATTEMPTS
                    if (!retry) {
                        if (resumable == null) discardPart() // a plain fetcher cannot resume
                        return DownloadResult.Failed(e.message ?: e.javaClass.simpleName, e)
                    }
                    try {
                        sleeper(retryDelaysMillis[failures - 1])
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
        } catch (e: Throwable) {
            // Unknown failure: never keep bytes we cannot vouch for.
            discardPart()
            return DownloadResult.Failed(e.message ?: e.javaClass.simpleName, e)
        }
    }

    fun delete(spec: ModelSpec) {
        val dest = fileFor(spec)
        verifiedCache.remove(dest.absolutePath)
        dest.delete()
        File(dest.path + ".tmp").delete()
        partFor(spec).delete()
        partMetaFor(spec).delete()
        dest.parentFile?.delete()
    }

    private data class Stamp(val sha256: String, val length: Long, val modified: Long)

    companion object {
        const val PART_SUFFIX = ".part"
        const val SHA_BUFFER_BYTES = 256 * 1024
        private const val MIB = 1024L * 1024L
        private const val MAX_ATTEMPTS = 50

        /** Pauses before retries 1..4 of consecutive failures that made no progress. */
        val DEFAULT_RETRY_DELAYS_MILLIS = listOf(2_000L, 5_000L, 15_000L, 30_000L)

        private val verifiedCache = ConcurrentHashMap<String, Stamp>()

        fun dir(context: Context): File = File(context.noBackupFilesDir, "models")

        /** Production wiring: app-private no-backup storage, private prefs, OkHttp download. */
        fun forApp(context: Context): ModelManager =
            ModelManager(dir(context), ModelConsent(context), OkHttpModelFetcher())

        fun sha256(file: File): String = file.inputStream().use { sha256(it) }

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(SHA_BUFFER_BYTES)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
    }
}
