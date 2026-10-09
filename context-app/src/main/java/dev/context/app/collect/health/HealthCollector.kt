package dev.context.app.collect.health

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import dev.context.app.collect.Collector
import dev.context.core.model.Event

/** Scaffold stub: reports itself as not ready, so the runner skips it and keeps no cursor. */
class HealthCollector(private val context: Context) : Collector {
  override val key = "health"

  override suspend fun missingPermissions(): List<String> = listOf("collector not implemented yet")

  override suspend fun collect(fromMs: Long, toMs: Long): List<Event> = emptyList()

  companion object {
    /** Every Health Connect permission this collector requests (the onboarding UI asks for exactly these). */
    val PERMISSIONS: Set<String> = setOf(
      HealthPermission.getReadPermission(SleepSessionRecord::class),
      HealthPermission.getReadPermission(HeartRateRecord::class),
      HealthPermission.getReadPermission(StepsRecord::class),
      "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
    )
  }
}
