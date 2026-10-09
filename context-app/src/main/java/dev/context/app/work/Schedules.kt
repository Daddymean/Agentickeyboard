package dev.context.app.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Every background job, in one place. No alarms, no foreground services: all
 * periodic work is WorkManager with battery-friendly constraints, so the OS
 * batches it with other apps' work.
 */
object Schedules {
  const val PIPELINE = "context.pipeline"
  const val SYNC = "context.sync"
  const val RETENTION = "context.retention"
  const val PIPELINE_NOW = "context.pipeline-now"
  const val DISTILL_SOON = "context.distill-soon"
  const val SYNC_NOW = "context.sync-now"

  private val batteryNotLow = Constraints.Builder().setRequiresBatteryNotLow(true).build()

  private val network = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .setRequiresBatteryNotLow(true)
    .build()

  /** Idempotent; called from [dev.context.app.ContextApp.onCreate]. */
  fun ensure(context: Context) {
    val wm = WorkManager.getInstance(context)
    wm.enqueueUniquePeriodicWork(
      PIPELINE,
      ExistingPeriodicWorkPolicy.UPDATE,
      PeriodicWorkRequestBuilder<PipelineWorker>(1, TimeUnit.HOURS).setConstraints(batteryNotLow).build(),
    )
    wm.enqueueUniquePeriodicWork(
      SYNC,
      ExistingPeriodicWorkPolicy.UPDATE,
      PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(network).build(),
    )
    wm.enqueueUniquePeriodicWork(
      RETENTION,
      ExistingPeriodicWorkPolicy.UPDATE,
      PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS)
        .setConstraints(Constraints.Builder().setRequiresCharging(true).build())
        .build(),
    )
  }

  /** Collect and distill now (onboarding "refresh" button). */
  fun runPipelineNow(context: Context) {
    WorkManager.getInstance(context)
      .enqueueUniqueWork(PIPELINE_NOW, ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<PipelineWorker>().build())
  }

  /** Re-distill shortly; repeated calls within the delay collapse into one run. */
  fun requestDistill(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
      DISTILL_SOON,
      ExistingWorkPolicy.KEEP,
      OneTimeWorkRequestBuilder<DistillWorker>().setInitialDelay(30, TimeUnit.SECONDS).build(),
    )
  }

  /** Sync as soon as the network allows (onboarding "sync now" button). */
  fun syncNow(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
      SYNC_NOW,
      ExistingWorkPolicy.REPLACE,
      OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .build(),
    )
  }
}
