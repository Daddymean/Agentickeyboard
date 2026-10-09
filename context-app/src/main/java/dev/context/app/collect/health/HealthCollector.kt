package dev.context.app.collect.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import dev.context.app.collect.Collector
import dev.context.app.collect.EventIds
import dev.context.app.collect.SensitivityPolicy
import dev.context.app.collect.health.HealthBuckets.StageKind
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import java.time.Duration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Reads sleep, heart rate and steps from Health Connect and emits summary
 * events (never raw samples):
 *
 * - `health.sleep`: one per finished [SleepSessionRecord], keyed by its record id.
 * - `health.heart_rate`: one per fully elapsed UTC hour with samples, `{min,max,avg,samples}`.
 * - `health.steps`: one per fully elapsed UTC hour with steps, `{count}`, from
 *   Health Connect's aggregation (which dedupes overlapping data origins).
 *
 * Hourly buckets are keyed by hour start, so see [HealthBuckets] for why only
 * complete hours are emitted. Data a wearable syncs after its hour was already
 * stored is not merged in; the bucket stays as first written.
 */
class HealthCollector(private val context: Context) : Collector {
  override val key = "health"

  override suspend fun missingPermissions(): List<String> {
    if (HealthConnectClient.getSdkStatus(context) != HealthConnectClient.SDK_AVAILABLE) {
      return listOf(NOT_AVAILABLE)
    }
    val granted = client().permissionController.getGrantedPermissions()
    return PERMISSIONS.filterNot { it in granted }
  }

  override suspend fun collect(fromMs: Long, toMs: Long): List<Event> {
    val client = client()
    return collectSleep(client, fromMs, toMs) +
      collectHeartRate(client, fromMs, toMs) +
      collectSteps(client, fromMs, toMs)
  }

  private suspend fun collectSleep(client: HealthConnectClient, fromMs: Long, toMs: Long): List<Event> {
    // Sessions are long; look back further so a night that started well before
    // the window (or was synced late) is still seen once it has ended.
    val sessions = readAll(client, SleepSessionRecord::class, fromMs - SLEEP_LOOKBACK_MS, toMs)
    return sessions
      .filter { it.endTime.toEpochMilli() <= toMs }
      .map { session ->
        val startMs = session.startTime.toEpochMilli()
        val endMs = session.endTime.toEpochMilli()
        val stages = session.stages.mapNotNull { stage ->
          stageKind(stage.stage)?.let {
            HealthBuckets.StageSpan(it, stage.startTime.toEpochMilli(), stage.endTime.toEpochMilli())
          }
        }
        event(
          type = EventTypes.HEALTH_SLEEP,
          id = EventIds.stable(EventTypes.HEALTH_SLEEP, session.metadata.id),
          startMs = startMs,
          endMs = endMs,
          payload = HealthBuckets.sleepPayload(startMs, endMs, stages),
          createdMs = toMs,
        )
      }
  }

  private suspend fun collectHeartRate(client: HealthConnectClient, fromMs: Long, toMs: Long): List<Event> {
    val (start, end) = HealthBuckets.completeHours(fromMs, toMs) ?: return emptyList()
    val samples = readAll(client, HeartRateRecord::class, start, end).flatMap { record ->
      record.samples.map { HealthBuckets.HrSample(it.time.toEpochMilli(), it.beatsPerMinute) }
    }
    return HealthBuckets.heartRateBuckets(samples, start, end).map { (hourStart, stats) ->
      event(
        type = EventTypes.HEALTH_HEART_RATE,
        id = EventIds.stable(EventTypes.HEALTH_HEART_RATE, hourStart),
        startMs = hourStart,
        endMs = hourStart + HealthBuckets.HOUR_MS,
        payload = HealthBuckets.heartRatePayload(stats),
        createdMs = toMs,
      )
    }
  }

  private suspend fun collectSteps(client: HealthConnectClient, fromMs: Long, toMs: Long): List<Event> {
    val (start, end) = HealthBuckets.completeHours(fromMs, toMs) ?: return emptyList()
    // Slices start at the (hour-aligned) filter start, so each one is a UTC hour.
    val groups = client.aggregateGroupByDuration(
      AggregateGroupByDurationRequest(
        metrics = setOf(StepsRecord.COUNT_TOTAL),
        timeRangeFilter = range(start, end),
        timeRangeSlicer = Duration.ofHours(1),
      ),
    )
    return groups.mapNotNull { group ->
      val count = group.result[StepsRecord.COUNT_TOTAL] ?: return@mapNotNull null
      if (count <= 0) return@mapNotNull null
      val hourStart = HealthBuckets.floorHour(group.startTime.toEpochMilli())
      if (hourStart < start || hourStart + HealthBuckets.HOUR_MS > end) return@mapNotNull null
      event(
        type = EventTypes.HEALTH_STEPS,
        id = EventIds.stable(EventTypes.HEALTH_STEPS, hourStart),
        startMs = hourStart,
        endMs = hourStart + HealthBuckets.HOUR_MS,
        payload = HealthBuckets.stepsPayload(count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()),
        createdMs = toMs,
      )
    }
  }

  /** Reads every record of [type] overlapping `[fromMs, toMs)`, following page tokens. */
  private suspend fun <T : Record> readAll(
    client: HealthConnectClient,
    type: KClass<T>,
    fromMs: Long,
    toMs: Long,
  ): List<T> {
    val records = mutableListOf<T>()
    var pageToken: String? = null
    do {
      val response = client.readRecords(
        ReadRecordsRequest(
          recordType = type,
          timeRangeFilter = range(fromMs, toMs),
          pageSize = PAGE_SIZE,
          pageToken = pageToken,
        ),
      )
      records += response.records
      pageToken = response.pageToken
    } while (pageToken != null)
    return records
  }

  private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

  private fun range(fromMs: Long, toMs: Long): TimeRangeFilter =
    TimeRangeFilter.between(Instant.ofEpochMilli(fromMs.coerceAtLeast(0)), Instant.ofEpochMilli(toMs))

  private fun event(type: String, id: String, startMs: Long, endMs: Long, payload: String, createdMs: Long) =
    Event(
      id = id,
      type = type,
      startMs = startMs,
      endMs = endMs,
      source = SOURCE,
      payload = payload,
      sensitivity = SensitivityPolicy.forType(type),
      createdMs = createdMs,
    )

  private fun stageKind(stage: Int): StageKind? = when (stage) {
    SleepSessionRecord.STAGE_TYPE_AWAKE,
    SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED,
    SleepSessionRecord.STAGE_TYPE_OUT_OF_BED,
    -> StageKind.AWAKE
    SleepSessionRecord.STAGE_TYPE_LIGHT -> StageKind.LIGHT
    SleepSessionRecord.STAGE_TYPE_DEEP -> StageKind.DEEP
    SleepSessionRecord.STAGE_TYPE_REM -> StageKind.REM
    // Generic "sleeping" and "unknown" carry no stage information.
    else -> null
  }

  companion object {
    /** Every Health Connect permission this collector requests (the onboarding UI asks for exactly these). */
    val PERMISSIONS: Set<String> = setOf(
      HealthPermission.getReadPermission(SleepSessionRecord::class),
      HealthPermission.getReadPermission(HeartRateRecord::class),
      HealthPermission.getReadPermission(StepsRecord::class),
      "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
    )

    const val SOURCE = "health_connect"
    const val NOT_AVAILABLE = "Health Connect not available"

    private const val PAGE_SIZE = 1000
    private const val SLEEP_LOOKBACK_MS = 24L * 60 * 60 * 1000
  }
}
