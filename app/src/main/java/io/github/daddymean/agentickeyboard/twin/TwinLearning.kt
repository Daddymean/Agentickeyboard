package io.github.daddymean.agentickeyboard.twin

import android.content.Context
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

    companion object {
        const val PREFS = "twin_settings"
        const val KEY_ENABLED = "twin_enabled"
        const val KEY_PAUSED = "twin_paused"
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

    /** Main-thread safe: queues the candidate and returns immediately. */
    fun submit(context: Context, candidate: TwinCaptureSession.Candidate, stillAllowed: () -> Boolean) {
        val appContext = context.applicationContext
        scope.launch {
            // Re-check: the user may have paused or deleted everything since the tap.
            if (!stillAllowed()) return@launch
            val text = TwinTextFilter.prepare(candidate.text) ?: return@launch
            runCatching { store(appContext).add(text, candidate.packageName, candidate.atMillis) }
                .onFailure { SafeLog.w(TAG, "Twin entry was not stored", it) }
        }
    }

    /** Runs [block] on the twin worker, after any queued writes. */
    suspend fun <T> onWorker(block: () -> T): T = withContext(worker) { block() }
}
