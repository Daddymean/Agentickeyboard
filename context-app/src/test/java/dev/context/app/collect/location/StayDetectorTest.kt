package dev.context.app.collect.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StayDetectorTest {
  private val home = 52.5200 to 13.4050
  private val cafe = 52.5300 to 13.4050 // ~1.1 km north
  private val min = 60_000L

  /** A fix [northM] metres north and [eastM] metres east of [origin] at minute [minute]. */
  private fun fix(origin: Pair<Double, Double>, minute: Long, northM: Double = 0.0, eastM: Double = 0.0, acc: Float = 20f) =
    Fix(
      timeMs = minute * min,
      lat = origin.first + northM / 111_195.0,
      lng = origin.second + eastM / (111_195.0 * Math.cos(Math.toRadians(origin.first))),
      accuracyM = acc,
    )

  @Test
  fun stayClosesWhenAFixFallsOutside() {
    val fixes = listOf(fix(home, 0), fix(home, 15), fix(home, 30), fix(cafe, 45), fix(cafe, 60))
    val result = StayDetector.detect(fixes, sinceMs = 0)
    assertEquals(1, result.stays.size)
    val stay = result.stays.single()
    assertEquals(0L, stay.startMs)
    assertEquals(30 * min, stay.endMs)
    assertEquals(45 * min, stay.closedAtMs)
    assertEquals(home.first, stay.lat, 1e-6)
    assertEquals(home.second, stay.lng, 1e-6)
    assertEquals(StayDetector.MIN_RADIUS_M, stay.radiusM)
    // The home stay closed after sinceMs, so nothing may be trimmed yet.
    assertEquals(0L, result.trimBeforeMs)
    // Once it is behind sinceMs, only the open cafe cluster is kept.
    assertEquals(45 * min, StayDetector.detect(fixes, sinceMs = 50 * min).trimBeforeMs)
  }

  @Test
  fun openStayIsNotEmittedAndIsKept() {
    val fixes = listOf(fix(home, 0), fix(home, 30), fix(home, 90))
    val result = StayDetector.detect(fixes, sinceMs = 60 * min)
    assertTrue(result.stays.isEmpty())
    assertEquals(0L, result.trimBeforeMs)
  }

  @Test
  fun jitterWithinRadiusAndAccuracyDoesNotSplitAStay() {
    val fixes = listOf(
      fix(home, 0),
      fix(home, 10, northM = 90.0),
      fix(home, 20, eastM = -120.0),
      fix(home, 30, northM = -60.0, eastM = 60.0),
      // Poor fix, but its accuracy covers the distance.
      fix(home, 40, northM = 230.0, acc = 120f),
      fix(home, 50),
      fix(cafe, 60),
      fix(cafe, 70),
    )
    val stays = StayDetector.detect(fixes, sinceMs = 0).stays
    assertEquals(1, stays.size)
    assertEquals(0L, stays.single().startMs)
    assertEquals(50 * min, stays.single().endMs)
    assertTrue(stays.single().radiusM in 100..300)
  }

  @Test
  fun aLoneOutlierDoesNotSplitAStay() {
    val fixes = listOf(fix(home, 0), fix(home, 15), fix(cafe, 30), fix(home, 45), fix(cafe, 60), fix(cafe, 75))
    val stays = StayDetector.detect(fixes, sinceMs = 0).stays
    assertEquals(listOf(0L to 45 * min), stays.map { it.startMs to it.endMs })
    assertEquals(60 * min, stays.single().closedAtMs)
  }

  @Test
  fun departureNeedsASecondFixAway() {
    val fixes = listOf(fix(home, 0), fix(home, 15), fix(home, 30), fix(cafe, 45))
    val result = StayDetector.detect(fixes, sinceMs = 40 * min)
    assertTrue(result.stays.isEmpty())
    assertEquals(0L, result.trimBeforeMs)
  }

  @Test
  fun wildlyInaccurateFixesAreIgnored() {
    val fixes = listOf(fix(home, 0), fix(cafe, 10, acc = 2_000f), fix(home, 20), fix(cafe, 30), fix(cafe, 40))
    val stays = StayDetector.detect(fixes, sinceMs = 0).stays
    assertEquals(listOf(0L to 20 * min), stays.map { it.startMs to it.endMs })
  }

  @Test
  fun shortStaysAreDropped() {
    val fixes = listOf(fix(home, 0), fix(home, 9), fix(cafe, 15), fix(cafe, 25), fix(home, 40), fix(home, 50))
    val stays = StayDetector.detect(fixes, sinceMs = 0).stays
    assertEquals(listOf(15 * min to 25 * min), stays.map { it.startMs to it.endMs })
  }

  @Test
  fun inputOrderAndDuplicatesDoNotMatter() {
    val fixes = listOf(fix(home, 0), fix(home, 15), fix(home, 30), fix(cafe, 45), fix(cafe, 60), fix(home, 75), fix(home, 90))
    val expected = StayDetector.detect(fixes, sinceMs = 0)
    val shuffled = StayDetector.detect((fixes + fixes.take(3)).reversed(), sinceMs = 0)
    assertEquals(expected, shuffled)
  }

  @Test
  fun staysClosedBeforeSinceAreTrimmedAtAClusterBoundary() {
    val fixes = listOf(
      fix(home, 0), fix(home, 30), // stay A, closed at 60
      fix(cafe, 60), fix(cafe, 90), // stay B, closed at 120
      fix(home, 120), fix(home, 150), // open
    )
    // B closed after sinceMs: it is emitted and the buffer keeps it.
    val result = StayDetector.detect(fixes, sinceMs = 100 * min)
    assertEquals(listOf(60 * min), result.stays.map { it.startMs })
    assertEquals(60 * min, result.trimBeforeMs)

    // Re-running on the trimmed buffer reproduces B exactly.
    val trimmed = fixes.filter { it.timeMs >= result.trimBeforeMs }
    assertEquals(result.stays, StayDetector.detect(trimmed, sinceMs = 100 * min).stays)
  }

  @Test
  fun trimDefaultsToSinceWhenNothingIsPending() {
    assertEquals(500L, StayDetector.detect(emptyList(), sinceMs = 500).trimBeforeMs)
  }

  @Test
  fun distanceIsRoughlyRight() {
    val d = StayDetector.distanceM(home.first, home.second, cafe.first, cafe.second)
    assertEquals(1_112.0, d, 5.0)
  }
}
