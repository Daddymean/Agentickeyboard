package io.github.daddymean.agentickeyboard.models

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
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
 */
fun interface ModelBytesFetcher {
    fun fetch(url: String, dest: File)
}

/** Streaming HTTP fetcher. Timeouts are long enough for a ~165 MB weight file. */
class OkHttpModelFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
) : ModelBytesFetcher {
    override fun fetch(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("download failed: HTTP ${response.code}")
            val body = response.body ?: error("download failed: empty body")
            dest.outputStream().use { out -> body.byteStream().copyTo(out) }
        }
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
 *   opted-in to false deletes that model's directory.
 * - [download] returns [DownloadResult.NotOptedIn] and writes nothing when the
 *   user has not opted in. It streams to a `.tmp` file, verifies SHA-256 against
 *   [ModelSpec.sha256], and only then renames into place. A mismatch or I/O
 *   failure deletes the temp and any previous dest file for that spec.
 * - [verifiedFile] returns the on-disk file only when it exists and matches the
 *   pin; a stale or tampered file is deleted and reported as absent.
 * - [delete] removes the model files without changing the opt-in flag.
 *
 * No twin memory or personal data is read or uploaded. The only network use is
 * the opt-in download of the pinned URL.
 */
class ModelManager(
    private val rootDir: File,
    private val consent: ModelConsent,
    private val fetcher: ModelBytesFetcher
) {
    fun isOptedIn(id: String): Boolean = consent.isOptedIn(id)

    fun setOptedIn(spec: ModelSpec, optedIn: Boolean) {
        consent.setOptedIn(spec.id, optedIn)
        if (!optedIn) delete(spec)
    }

    fun fileFor(spec: ModelSpec): File = File(File(rootDir, spec.id), spec.fileName)

    fun verifiedFile(spec: ModelSpec): File? {
        val file = fileFor(spec)
        if (!file.isFile) return null
        val actual = sha256(file)
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            file.delete()
            File(file.path + ".tmp").delete()
            return null
        }
        return file
    }

    fun isReady(spec: ModelSpec): Boolean = verifiedFile(spec) != null

    fun download(spec: ModelSpec): DownloadResult {
        if (!consent.isOptedIn(spec.id)) return DownloadResult.NotOptedIn
        val dest = fileFor(spec)
        val tmp = File(dest.path + ".tmp")
        tmp.parentFile?.mkdirs()
        if (tmp.exists()) tmp.delete()
        return try {
            fetcher.fetch(spec.downloadUrl, tmp)
            val actual = sha256(tmp)
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                tmp.delete()
                if (dest.exists()) dest.delete()
                DownloadResult.HashMismatch(actual)
            } else {
                if (dest.exists() && !dest.delete()) {
                    tmp.delete()
                    return DownloadResult.Failed("could not replace existing model file")
                }
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    return DownloadResult.Failed("could not move verified model file into place")
                }
                DownloadResult.Ready(dest)
            }
        } catch (e: Throwable) {
            tmp.delete()
            DownloadResult.Failed(e.message ?: e.javaClass.simpleName, e)
        }
    }

    fun delete(spec: ModelSpec) {
        val dest = fileFor(spec)
        dest.delete()
        File(dest.path + ".tmp").delete()
        dest.parentFile?.delete()
    }

    companion object {
        fun dir(context: Context): File = File(context.noBackupFilesDir, "models")

        /** Production wiring: app-private no-backup storage, private prefs, OkHttp download. */
        fun forApp(context: Context): ModelManager =
            ModelManager(dir(context), ModelConsent(context), OkHttpModelFetcher())

        fun sha256(file: File): String = file.inputStream().use { sha256(it) }

        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
            return digest.digest().joinToString("") { b -> "%02x".format(b) }
        }
    }
}
