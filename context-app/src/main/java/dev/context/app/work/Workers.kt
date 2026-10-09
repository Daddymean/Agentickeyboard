package dev.context.app.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import dev.context.app.collect.CollectRunner
import dev.context.app.distill.DistillRunner
import dev.context.app.graph
import dev.context.app.settings.SettingsStore
import kotlin.coroutines.cancellation.CancellationException

/** Hourly: collect from every ready collector, then distill. */
class PipelineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val graph = applicationContext.graph
    val now = System.currentTimeMillis()
    // Collector failures are isolated and recorded per collector; never retried here.
    CollectRunner(graph.db, graph.settings, graph.collectors).runAll(now)
    return runCatchingRetry { DistillRunner(graph.db, graph.snapshots, graph.distiller).run(now) }
  }
}

/** One-off re-distill after new events arrive over IPC. */
class DistillWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val graph = applicationContext.graph
    return runCatchingRetry { DistillRunner(graph.db, graph.snapshots, graph.distiller).run(System.currentTimeMillis()) }
  }
}

/** Every 6 h on a network: push the syncable slice to Supabase. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val graph = applicationContext.graph
    val now = System.currentTimeMillis()
    return try {
      val result = graph.sync.syncOnce(now)
      graph.settings.putString(SettingsStore.statusKey(STATUS), result.summary)
      graph.settings.putLong(SettingsStore.statusAtKey(STATUS), now)
      Result.success()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      graph.settings.putString(SettingsStore.statusKey(STATUS), "error: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
      graph.settings.putLong(SettingsStore.statusAtKey(STATUS), now)
      if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }
  }

  companion object {
    const val STATUS = "sync"
  }
}

/** Daily while charging: bounds on-device storage. */
class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
  override suspend fun doWork(): Result {
    val graph = applicationContext.graph
    val now = System.currentTimeMillis()
    graph.db.events().deleteStartedBefore(now - RAW_EVENT_RETENTION_MS)
    // Hard deletes are invisible to sync, which is intended here: the cloud
    // keeps its history of old episodes.
    graph.db.episodes().deleteEndedBefore(now - EPISODE_RETENTION_MS)
    return Result.success()
  }

  companion object {
    const val RAW_EVENT_RETENTION_MS = 90L * 24 * 60 * 60 * 1000
    const val EPISODE_RETENTION_MS = 730L * 24 * 60 * 60 * 1000
  }
}

private const val MAX_ATTEMPTS = 5

/** Success, or retry with WorkManager's backoff until [MAX_ATTEMPTS], then give up until the next period. */
private suspend fun CoroutineWorker.runCatchingRetry(block: suspend () -> Unit): ListenableWorker.Result =
  try {
    block()
    ListenableWorker.Result.success()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    if (runAttemptCount < MAX_ATTEMPTS) ListenableWorker.Result.retry() else ListenableWorker.Result.failure()
  }
