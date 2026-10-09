package dev.context.app.collect.location

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One raw location fix as buffered by [LocationUpdatesReceiver]. [timeMs] is wall-clock UTC. */
@Serializable
data class Fix(
  val timeMs: Long,
  val lat: Double,
  val lng: Double,
  /** Horizontal accuracy (68% radius) in metres; [UNKNOWN_ACCURACY_M] when the fix had none. */
  val accuracyM: Float,
) {
  companion object {
    const val UNKNOWN_ACCURACY_M = 9_999f
  }
}

/** One activity transition as buffered by [ActivityTransitionReceiver]. */
@Serializable
data class Transition(
  val timeMs: Long,
  /** One of [Activities]. */
  val activity: String,
  /** `true` for ENTER, `false` for EXIT. */
  val enter: Boolean,
)

/** Activity names used in buffered [Transition]s and in `location.activity` payloads. */
object Activities {
  const val WALKING = "walking"
  const val RUNNING = "running"
  const val CYCLING = "cycling"
  const val IN_VEHICLE = "in_vehicle"
  const val STILL = "still"
}

/** A closed stay: the device remained within [radiusM] of ([lat], [lng]) from [startMs] to [endMs]. */
data class Stay(
  val startMs: Long,
  val endMs: Long,
  val lat: Double,
  val lng: Double,
  val radiusM: Int,
  /** Time of the first fix of the departure, i.e. when the stay was over. */
  val closedAtMs: Long,
)

/** A closed activity segment from [startMs] to [endMs]. */
data class ActivitySegment(val activity: String, val startMs: Long, val endMs: Long)

/**
 * Turns buffered fixes into stays. Pure and deterministic: the same fixes
 * always give the same stays, which keeps event ids stable across re-runs.
 *
 * Fixes are clustered in time order. A fix joins the current cluster when it
 * lies within [radiusM] of the cluster's accuracy-weighted centroid, plus up to
 * another [radiusM] of slack for the fix's own accuracy (so GPS/Wi-Fi jitter
 * doesn't split a stay). A fix outside closes the cluster and starts a new one,
 * unless the next fix is back inside (a lone outlier is skipped); until that
 * next fix arrives the stay stays open. The last cluster is still open — the
 * device may still be there — so it is never emitted. A stay ends at its last
 * fix inside, so durations are conservative by up to one update interval.
 */
object StayDetector {
  const val RADIUS_M = 150.0
  const val MIN_STAY_MS = 10L * 60 * 1000

  /** Fixes worse than this are ignored entirely: they neither join nor close a stay. */
  const val MAX_ACCURACY_M = 500f

  /** Lower bound for a stay's reported radius. */
  const val MIN_RADIUS_M = 10

  data class Result(
    /** Closed stays of at least the minimum duration that closed at or after `sinceMs`, oldest first. */
    val stays: List<Stay>,
    /**
     * Buffered fixes older than this can be dropped: they belong only to stays
     * that closed before `sinceMs`. Always a cluster boundary (or `sinceMs`), so
     * re-running on the trimmed buffer reproduces every later stay exactly, and
     * it never cuts into the open stay.
     */
    val trimBeforeMs: Long,
  )

  fun detect(
    fixes: List<Fix>,
    sinceMs: Long,
    radiusM: Double = RADIUS_M,
    minStayMs: Long = MIN_STAY_MS,
  ): Result {
    val usable = fixes
      .filter { it.accuracyM <= MAX_ACCURACY_M && it.lat in -90.0..90.0 && it.lng in -180.0..180.0 }
      .distinct()
      .sortedWith(compareBy({ it.timeMs }, { it.lat }, { it.lng }, { it.accuracyM }))

    val closed = mutableListOf<Pair<Cluster, Long>>()
    var current: Cluster? = null
    for ((index, fix) in usable.withIndex()) {
      val cluster = current
      if (cluster == null) {
        current = Cluster(fix)
        continue
      }
      if (cluster.accepts(fix, radiusM)) {
        cluster.add(fix)
        continue
      }
      // A lone outlier doesn't end a stay: departure needs the next fix to stay
      // away too. Without a next fix yet, the stay simply remains open.
      val next = usable.getOrNull(index + 1) ?: break
      if (cluster.accepts(next, radiusM)) continue
      closed += cluster to fix.timeMs
      current = Cluster(fix)
    }

    val relevant = closed.filter { (_, closedAt) -> closedAt >= sinceMs }
    val stays = relevant
      .filter { (cluster, _) -> cluster.endMs - cluster.startMs >= minStayMs }
      .map { (cluster, closedAt) -> cluster.toStay(closedAt) }
    val trimBefore = (relevant.map { it.first.startMs } + listOfNotNull(current?.startMs, sinceMs)).min()
    return Result(stays, trimBefore)
  }

  private class Cluster(first: Fix) {
    private val members = mutableListOf<Fix>()
    private var weightSum = 0.0
    private var latSum = 0.0
    private var lngSum = 0.0
    val startMs = first.timeMs
    var endMs = first.timeMs
      private set

    val lat get() = latSum / weightSum
    val lng get() = lngSum / weightSum

    init {
      add(first)
    }

    fun accepts(fix: Fix, radiusM: Double): Boolean =
      distanceM(lat, lng, fix.lat, fix.lng) <= radiusM + min(fix.accuracyM.toDouble(), radiusM)

    fun add(fix: Fix) {
      val accuracy = max(fix.accuracyM.toDouble(), 10.0)
      val weight = 1.0 / (accuracy * accuracy)
      weightSum += weight
      latSum += fix.lat * weight
      lngSum += fix.lng * weight
      endMs = max(endMs, fix.timeMs)
      members += fix
    }

    fun toStay(closedAtMs: Long): Stay {
      val cLat = lat
      val cLng = lng
      val spread = members.maxOf { distanceM(cLat, cLng, it.lat, it.lng) }
      return Stay(startMs, endMs, cLat, cLng, ceil(spread).toInt().coerceAtLeast(MIN_RADIUS_M), closedAtMs)
    }
  }

  /** Great-circle (haversine) distance in metres. */
  fun distanceM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
      cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
  }

  private const val EARTH_RADIUS_M = 6_371_008.8
}

/**
 * Pairs buffered activity transitions into closed segments. Pure and
 * deterministic.
 *
 * An ENTER opens a segment for its activity (a repeated ENTER keeps the
 * earlier start, so re-registration doesn't restart it) and closes any other
 * open activity at the same instant, since activities are mutually exclusive
 * and an EXIT can be lost. An EXIT closes its own activity; an EXIT with
 * nothing open is ignored. Segments still open at the end are never emitted.
 */
object TransitionPairer {
  data class Result(
    /** Closed, non-empty segments that ended at or after `sinceMs`, oldest first. */
    val segments: List<ActivitySegment>,
    /** Buffered transitions older than this can be dropped without changing any later segment. */
    val trimBeforeMs: Long,
  )

  fun pair(transitions: List<Transition>, sinceMs: Long): Result {
    // EXIT before ENTER at the same instant: "left A, entered B".
    val sorted = transitions.distinct().sortedWith(compareBy({ it.timeMs }, { it.enter }, { it.activity }))
    val open = LinkedHashMap<String, Long>()
    val closed = mutableListOf<ActivitySegment>()
    for (t in sorted) {
      if (t.enter) {
        val others = open.keys.filter { it != t.activity }
        for (activity in others) closed += ActivitySegment(activity, open.remove(activity)!!, t.timeMs)
        open.putIfAbsent(t.activity, t.timeMs)
      } else {
        open.remove(t.activity)?.let { start -> closed += ActivitySegment(t.activity, start, t.timeMs) }
      }
    }

    val relevant = closed.filter { it.endMs >= sinceMs }
    val segments = relevant.filter { it.endMs > it.startMs }.sortedWith(compareBy({ it.startMs }, { it.activity }))
    val trimBefore = (relevant.map { it.startMs } + open.values + sinceMs).min()
    return Result(segments, trimBefore)
  }
}
