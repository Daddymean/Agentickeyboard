package dev.context.app.collect.health

import dev.context.app.collect.health.HealthBuckets.HOUR_MS
import dev.context.app.collect.health.HealthBuckets.HrSample
import dev.context.app.collect.health.HealthBuckets.HrStats
import dev.context.app.collect.health.HealthBuckets.StageKind
import dev.context.app.collect.health.HealthBuckets.StageSpan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HealthBucketsTest {
  private val minute = 60_000L
  private val base = 1_700_000_000_000L / HOUR_MS * HOUR_MS

  @Test
  fun completeHoursWidensStartAndStopsAtLastFinishedHour() {
    assertEquals(base to base + 2 * HOUR_MS, HealthBuckets.completeHours(base + 10 * minute, base + 2 * HOUR_MS + 5 * minute))
    assertEquals(base to base + HOUR_MS, HealthBuckets.completeHours(base, base + HOUR_MS))
    assertNull(HealthBuckets.completeHours(base + minute, base + 59 * minute))
  }

  @Test
  fun heartRateBucketsAggregatePerHourAndSkipEmptyOrOutOfRangeSamples() {
    val samples = listOf(
      HrSample(base + minute, 60),
      HrSample(base + 30 * minute, 81),
      HrSample(base + 59 * minute, 70),
      // Hour 1 is empty; hour 2 has one sample.
      HrSample(base + 2 * HOUR_MS + minute, 100),
      // Outside the span on both sides.
      HrSample(base - 1, 200),
      HrSample(base + 3 * HOUR_MS, 40),
    )
    val buckets = HealthBuckets.heartRateBuckets(samples, base, base + 3 * HOUR_MS)
    assertEquals(listOf(base, base + 2 * HOUR_MS), buckets.keys.toList())
    assertEquals(HrStats(min = 60, max = 81, avg = 70, samples = 3), buckets[base])
    assertEquals(HrStats(min = 100, max = 100, avg = 100, samples = 1), buckets[base + 2 * HOUR_MS])
  }

  @Test
  fun heartRateAndStepsPayloadsAreJsonObjects() {
    val hr = Json.parseToJsonElement(HealthBuckets.heartRatePayload(HrStats(50, 90, 72, 12))).jsonObject
    assertEquals(50, hr["min"]!!.jsonPrimitive.int)
    assertEquals(90, hr["max"]!!.jsonPrimitive.int)
    assertEquals(72, hr["avg"]!!.jsonPrimitive.int)
    assertEquals(12, hr["samples"]!!.jsonPrimitive.int)
    assertEquals("""{"count":345}""", HealthBuckets.stepsPayload(345))
  }

  @Test
  fun sleepPayloadSumsStagesClippedToTheSession() {
    val start = base
    val end = base + 8 * HOUR_MS
    val stages = listOf(
      // Starts 10 min before the session: only 20 min count.
      StageSpan(StageKind.AWAKE, start - 10 * minute, start + 20 * minute),
      StageSpan(StageKind.LIGHT, start + 20 * minute, start + 3 * HOUR_MS),
      StageSpan(StageKind.DEEP, start + 3 * HOUR_MS, start + 5 * HOUR_MS),
      StageSpan(StageKind.REM, start + 5 * HOUR_MS, start + 6 * HOUR_MS),
      StageSpan(StageKind.LIGHT, start + 6 * HOUR_MS, end),
    )
    val payload = Json.parseToJsonElement(HealthBuckets.sleepPayload(start, end, stages)).jsonObject
    assertEquals(480, payload["durationMin"]!!.jsonPrimitive.int)
    val stagesMin = payload["stagesMin"]!!.jsonObject
    assertEquals(20, stagesMin["awake"]!!.jsonPrimitive.int)
    assertEquals(160 + 120, stagesMin["light"]!!.jsonPrimitive.int)
    assertEquals(120, stagesMin["deep"]!!.jsonPrimitive.int)
    assertEquals(60, stagesMin["rem"]!!.jsonPrimitive.int)
  }

  @Test
  fun sleepPayloadOmitsStagesWhenNoneRecorded() {
    val payload = Json.parseToJsonElement(HealthBuckets.sleepPayload(base, base + 90 * minute, emptyList())).jsonObject
    assertEquals(90, payload["durationMin"]!!.jsonPrimitive.int)
    assertFalse("stagesMin" in payload)
  }

  @Test
  fun minutesRoundToNearest() {
    assertEquals(0, HealthBuckets.toMinutes(29_999))
    assertEquals(1, HealthBuckets.toMinutes(30_000))
    assertEquals(0, HealthBuckets.toMinutes(-5 * minute))
  }

  @Test
  fun floorHourHandlesNegativeTimes() {
    assertEquals(-HOUR_MS, HealthBuckets.floorHour(-1))
    assertEquals(base, HealthBuckets.floorHour(base + HOUR_MS - 1))
  }
}
