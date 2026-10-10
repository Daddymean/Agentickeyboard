package io.github.daddymean.agentickeyboard.twin

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.daddymean.agentickeyboard.util.SafeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** KEYBOARD-024 switches. On by default (Keith, 2026-10-10: "learn as much as possible"). */
class TwinPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var isPaused: Boolean
        get() = prefs.getBoolean(KEY_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_PAUSED, value).apply()

    /** Set while capture is paused because the phone has under 500 MB free. */
    var isLowStoragePaused: Boolean
        get() = prefs.getBoolean(KEY_LOW_STORAGE, false)
        set(value) = prefs.edit().putBoolean(KEY_LOW_STORAGE, value).apply()

    /** Highest 64 MB step already announced, so each notice shows once. */
    var announcedSizeTier: Int
        get() = prefs.getInt(KEY_SIZE_TIER, 0)
        set(value) = prefs.edit().putInt(KEY_SIZE_TIER, value).apply()

    companion object {
        const val PREFS = "twin_settings"
        const val KEY_ENABLED = "twin_enabled"
        const val KEY_PAUSED = "twin_paused"
        const val KEY_LOW_STORAGE = "twin_low_storage_paused"
        const val KEY_SIZE_TIER = "twin_announced_size_tier"
    }
}

/**
 * KEYBOARD-024: the one place that writes to the twin store. The keyboard hands it a
 * [TwinCaptureSession.Candidate] on the main thread and returns at once; filtering,
 * encryption and the database write run on a single background worker, in order.
 * Errors are logged without content and never reach the keyboard.
 */
object TwinLearning {
    private const val TAG = "TwinLearning"

    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + worker)

    @Volatile private var store: TwinStore? = null

    fun store(context: Context): TwinStore =
        store ?: synchronized(this) {
            store ?: TwinStore(context, TwinStore.storeFile(context), KeystoreTwinCipher()).also { store = it }
        }

    private fun writer(context: Context) = TwinWriter(
        store = store(context),
        prefs = TwinPreferences(context),
        freeBytes = { TwinStore.storeFile(context).parentFile?.let { it.mkdirs(); it.usableSpace } ?: 0L },
        notify = { message ->
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    )

    /** Main-thread safe: queues the candidate and returns immediately. */
    fun submit(context: Context, candidate: TwinCaptureSession.Candidate, stillAllowed: () -> Boolean) {
        val appContext = context.applicationContext
        scope.launch {
            // Re-check: the user may have paused or deleted everything since the tap.
            if (!stillAllowed()) return@launch
            val text = TwinTextFilter.prepare(candidate.text) ?: return@launch
            runCatching { writer(appContext).write(text, candidate.packageName, candidate.atMillis) }
                .onFailure { SafeLog.w(TAG, "Twin entry was not stored", it) }
        }
    }

    /** Runs [block] on the twin worker, after any queued writes. */
    suspend fun <T> onWorker(block: () -> T): T = withContext(worker) { block() }
}
