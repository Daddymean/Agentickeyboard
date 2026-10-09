package dev.context.app.collect.health

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Pure bucketing and payload logic for [HealthCollector], kept free of Android
 * and Health Connect types so it runs on the plain JVM in unit tests.
 *
 * Hour buckets are aligned to **UTC epoch hours** (`floor(ms / 1h) * 1h`), not
 * local wall-clock hours; for whole-hour time zones the two coincide. Only
 * fully elapsed hours are ever emitted: a bucket's id is derived from its start
 * alone, so a bucket stored while still filling would be frozen incomplete by
 * `INSERT OR IGNORE` when the overlap window re-reads it.
 */
internal object HealthBuckets {
  const val HOUR_MS = 60L * 60 * 1000
  private const val MINUTE_MS = 60L * 1000

  /** Start of the UTC hour containing [ms]. */
  fun floorHour(ms: Long): Long = Math.floorDiv(ms, HOUR_MS) * HOUR_MS

  /**
   * The hour-aligned span `[start, end)` covering every fully elapsed hour that
   * touches `[fromMs, toMs)`. The start is widened down to its hour boundary so
   * the first bucket is read in full; the end is the last completed hour. Null
   * when no complete hour exists.
   */
  fun completeHours(fromMs: Long, toMs: Long): Pair<Long, Long>? {
    val start = floorHour(fromMs)
    val end = floorHour(toMs)
    return if (end > start) start to end else null
  }

  /** One heart-rate reading. */
  data class HrSample(val timeMs: Long, val bpm: Long)

  data class HrStats(val min: Int, val max: Int, val avg: Int, val samples: Int)

  /**
   * Groups [samples] into hour buckets within `[startMs, endMs)` (both expected
   * hour-aligned), keyed and sorted by hour start. Samples outside the span are
   * dropped and empty hours are absent.
   */
  fun heartRateBuckets(samples: List<HrSample>, startMs: Long, endMs: Long): Map<Long, HrStats> =
    samples
      .filter { it.timeMs in startMs until endMs }
      .groupBy { floorHour(it.timeMs) }
      .toSortedMap()
      .mapValues { (_, inHour) ->
        val bpms = inHour.map { it.bpm }
        HrStats(
          min = bpms.min().toInt(),
          max = bpms.max().toInt(),
          avg = Math.round(bpms.sum().toDouble() / bpms.size).toInt(),
          samples = bpms.size,
        )
      }

  fun heartRatePayload(stats: HrStats): String = buildJsonObject {
    put("min", stats.min)
    put("max", stats.max)
    put("avg", stats.avg)
    put("samples", stats.samples)
  }.toString()

  fun stepsPayload(count: Int): String = buildJsonObject { put("count", count) }.toString()

  /** The sleep stages the payload reports; other Health Connect stage types are ignored. */
  enum class StageKind { AWAKE, LIGHT, DEEP, REM }

  data class StageSpan(val kind: StageKind, val startMs: Long, val endMs: Long)

  /**
   * `{"durationMin":Int,"stagesMin":{"awake":Int,"light":Int,"deep":Int,"rem":Int}}`.
   * Stages are clipped to the session; `stagesMin` is omitted when no stage time
   * was recorded.
   */
  fun sleepPayload(startMs: Long, endMs: Long, stages: List<StageSpan>): String {
    val stageMs = LongArray(StageKind.entries.size)
    for (span in stages) {
      val clipped = minOf(span.endMs, endMs) - maxOf(span.startMs, startMs)
      if (clipped > 0) stageMs[span.kind.ordinal] += clipped
    }
    return buildJsonObject {
      put("durationMin", toMinutes(endMs - startMs))
      if (stageMs.any { it > 0 }) {
        putJsonObject("stagesMin") {
          put("awake", toMinutes(stageMs[StageKind.AWAKE.ordinal]))
          put("light", toMinutes(stageMs[StageKind.LIGHT.ordinal]))
          put("deep", toMinutes(stageMs[StageKind.DEEP.ordinal]))
          put("rem", toMinutes(stageMs[StageKind.REM.ordinal]))
        }
      }
    }.toString()
  }

  /** Rounds to the nearest minute; negative spans count as zero. */
  fun toMinutes(ms: Long): Int = ((ms.coerceAtLeast(0) + MINUTE_MS / 2) / MINUTE_MS).toInt()
}
