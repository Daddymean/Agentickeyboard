package dev.context.app.collect.location

import android.content.BroadcastReceiver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "LocationReceivers"

/** Receivers get ~10 s after `goAsync()`; leave headroom. */
private const val ASYNC_BUDGET_MS = 8_000L

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs [block] off the main thread with `goAsync()`, finishing the broadcast
 * when it completes, fails or runs out of time. Failures are logged, never
 * thrown: losing one batch of fixes is better than crashing the process.
 */
internal fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
  val pending = goAsync()
  receiverScope.launch {
    try {
      withTimeout(ASYNC_BUDGET_MS) { block() }
    } catch (e: Exception) {
      Log.w(TAG, "dropping location broadcast: ${e.javaClass.simpleName}: ${e.message}")
    } finally {
      pending.finish()
    }
  }
}
