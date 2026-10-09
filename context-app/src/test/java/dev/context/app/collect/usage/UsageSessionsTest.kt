package dev.context.app.collect.usage

import android.app.usage.UsageEvents
import dev.context.app.collect.usage.UsageSessions.Kind.CLOSE
import dev.context.app.collect.usage.UsageSessions.Kind.CLOSE_ALL
import dev.context.app.collect.usage.UsageSessions.Kind.OPEN
import dev.context.app.collect.usage.UsageSessions.Span
import dev.context.app.collect.usage.UsageSessions.Transition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageSessionsTest {
  private fun t(timeMs: Long, pkg: String, kind: UsageSessions.Kind) = Transition(timeMs, pkg, kind)

  private val s = 1_000L

  @Test
  fun simpleResumePauseIsOneSession() {
    val spans = UsageSessions.build(listOf(t(0, "a", OPEN), t(10 * s, "a", CLOSE)))
    assertEquals(listOf(Span("a", 0, 10 * s)), spans)
  }

  @Test
  fun stoppedAlsoClosesAndALaterStopIsANoOp() {
    val spans = UsageSessions.build(
      listOf(t(0, "a", OPEN), t(10 * s, "a", CLOSE), t(11 * s, "a", CLOSE)),
    )
    assertEquals(listOf(Span("a", 0, 10 * s)), spans)
  }

  @Test
  fun interleavedPackagesArePairedPerPackage() {
    val spans = UsageSessions.build(
      listOf(
        t(0, "a", OPEN),
        t(20 * s, "a", CLOSE),
        t(20 * s + 100, "b", OPEN),
        t(40 * s, "b", CLOSE),
        t(41 * s, "a", OPEN),
        t(60 * s, "a", CLOSE),
      ),
    )
    assertEquals(
      listOf(Span("a", 0, 20 * s), Span("b", 20 * s + 100, 40 * s), Span("a", 41 * s, 60 * s)),
      spans,
    )
  }

  @Test
  fun overlappingSpansOfDifferentPackagesBothSurvive() {
    // Split screen: both resumed at once.
    val spans = UsageSessions.build(
      listOf(t(0, "a", OPEN), t(5 * s, "b", OPEN), t(30 * s, "a", CLOSE), t(40 * s, "b", CLOSE)),
    )
    assertEquals(listOf(Span("a", 0, 30 * s), Span("b", 5 * s, 40 * s)), spans)
  }

  @Test
  fun inputIsSortedByTime() {
    val spans = UsageSessions.build(listOf(t(10 * s, "a", CLOSE), t(0, "a", OPEN)))
    assertEquals(listOf(Span("a", 0, 10 * s)), spans)
  }

  @Test
  fun missingPauseIsClosedByScreenOff() {
    val spans = UsageSessions.build(
      listOf(t(0, "a", OPEN), t(10 * s, "b", OPEN), t(30 * s, "b", CLOSE), t(60 * s, "android", CLOSE_ALL)),
    )
    assertEquals(listOf(Span("a", 0, 60 * s), Span("b", 10 * s, 30 * s)), spans)
  }

  @Test
  fun repeatedResumeKeepsTheEarliestStart() {
    val spans = UsageSessions.build(listOf(t(0, "a", OPEN), t(5 * s, "a", OPEN), t(20 * s, "a", CLOSE)))
    assertEquals(listOf(Span("a", 0, 20 * s)), spans)
  }

  @Test
  fun spanStillOpenAtTheEndIsNotEmitted() {
    val spans = UsageSessions.build(listOf(t(0, "a", OPEN), t(10 * s, "a", CLOSE), t(20 * s, "a", OPEN)))
    assertEquals(listOf(Span("a", 0, 10 * s)), spans)
  }

  @Test
  fun closeWithoutOpenIsIgnored() {
    // The resume happened before the query window.
    assertEquals(emptyList<Span>(), UsageSessions.build(listOf(t(10 * s, "a", CLOSE))))
  }

  @Test
  fun screenOffAndShutdownCloseEverythingOpen() {
    val spans = UsageSessions.build(
      listOf(
        t(0, "a", OPEN),
        t(1 * s, "b", OPEN),
        t(30 * s, "", CLOSE_ALL),
        t(31 * s, "a", CLOSE), // late pause after screen-off: no-op
        t(40 * s, "c", OPEN),
        t(50 * s, "", CLOSE_ALL),
      ),
    )
    assertEquals(listOf(Span("a", 0, 30 * s), Span("b", 1 * s, 30 * s), Span("c", 40 * s, 50 * s)), spans)
  }

  @Test
  fun shortSpansAreDropped() {
    val spans = UsageSessions.build(
      listOf(
        t(0, "a", OPEN), t(4_999, "a", CLOSE),
        t(10 * s, "b", OPEN), t(15 * s, "b", CLOSE),
      ),
    )
    assertEquals(listOf(Span("b", 10 * s, 15 * s)), spans)
  }

  @Test
  fun samePackageSpansWithASmallGapMerge() {
    // Activity to activity inside one app: two 3 s spans with a 1.9 s gap are one 7.9 s session.
    val spans = UsageSessions.build(
      listOf(t(0, "a", OPEN), t(3 * s, "a", CLOSE), t(4_900, "a", OPEN), t(7_900, "a", CLOSE)),
    )
    assertEquals(listOf(Span("a", 0, 7_900)), spans)
  }

  @Test
  fun gapOfTwoSecondsOrMoreDoesNotMerge() {
    val spans = UsageSessions.build(
      listOf(t(0, "a", OPEN), t(10 * s, "a", CLOSE), t(12 * s, "a", OPEN), t(22 * s, "a", CLOSE)),
    )
    assertEquals(listOf(Span("a", 0, 10 * s), Span("a", 12 * s, 22 * s)), spans)
  }

  @Test
  fun mergeChainsAcrossManyFragments() {
    val transitions = (0 until 5).flatMap { i ->
      val start = i * 2_500L
      listOf(t(start, "a", OPEN), t(start + 1_500, "a", CLOSE))
    }
    assertEquals(listOf(Span("a", 0, 11_500)), UsageSessions.build(transitions))
  }

  @Test
  fun mergeDoesNotJoinDifferentPackages() {
    val spans = UsageSessions.build(
      listOf(t(0, "a", OPEN), t(3 * s, "a", CLOSE), t(3_500, "b", OPEN), t(7 * s, "b", CLOSE)),
    )
    assertEquals(emptyList<Span>(), spans)
  }

  @Test
  fun excludedPackagesAreIgnoredAndDoNotSplitSessions() {
    val spans = UsageSessions.build(
      listOf(
        t(0, "app", OPEN),
        t(10 * s, "app", CLOSE),
        t(10 * s, "launcher", OPEN),
        t(10_500, "launcher", CLOSE),
        t(11 * s, "app", OPEN),
        t(30 * s, "app", CLOSE),
        t(31 * s, "ime", OPEN),
        t(60 * s, "ime", CLOSE),
      ),
      excluded = setOf("launcher", "ime"),
    )
    assertEquals(listOf(Span("app", 0, 30 * s)), spans)
  }

  @Test
  fun eventTypeMapping() {
    assertEquals(OPEN, UsageSessions.kindOf(UsageEvents.Event.ACTIVITY_RESUMED))
    assertEquals(CLOSE, UsageSessions.kindOf(UsageEvents.Event.ACTIVITY_PAUSED))
    assertEquals(CLOSE, UsageSessions.kindOf(UsageEvents.Event.ACTIVITY_STOPPED))
    assertEquals(CLOSE_ALL, UsageSessions.kindOf(UsageEvents.Event.SCREEN_NON_INTERACTIVE))
    assertEquals(CLOSE_ALL, UsageSessions.kindOf(UsageEvents.Event.DEVICE_SHUTDOWN))
    assertNull(UsageSessions.kindOf(UsageEvents.Event.CONFIGURATION_CHANGE))
    assertNull(UsageSessions.kindOf(UsageEvents.Event.SCREEN_INTERACTIVE))
  }
}
