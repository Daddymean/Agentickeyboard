package dev.context.app.collect.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitionPairerTest {
  private fun enter(activity: String, t: Long) = Transition(t, activity, enter = true)
  private fun exit(activity: String, t: Long) = Transition(t, activity, enter = false)

  @Test
  fun pairsEnterAndExitPerActivity() {
    val result = TransitionPairer.pair(
      listOf(
        enter(Activities.STILL, 0), exit(Activities.STILL, 100),
        enter(Activities.WALKING, 100), exit(Activities.WALKING, 250),
        enter(Activities.IN_VEHICLE, 250),
      ),
      sinceMs = 0,
    )
    assertEquals(
      listOf(
        ActivitySegment(Activities.STILL, 0, 100),
        ActivitySegment(Activities.WALKING, 100, 250),
      ),
      result.segments,
    )
    // The vehicle segment is still open.
    assertEquals(0L, result.trimBeforeMs)
  }

  @Test
  fun openSegmentIsNotEmittedAndIsKept() {
    val result = TransitionPairer.pair(listOf(enter(Activities.RUNNING, 10)), sinceMs = 500)
    assertTrue(result.segments.isEmpty())
    assertEquals(10L, result.trimBeforeMs)
  }

  @Test
  fun enterOfAnotherActivityClosesAMissedExit() {
    val segments = TransitionPairer.pair(
      listOf(enter(Activities.STILL, 0), enter(Activities.CYCLING, 60), exit(Activities.CYCLING, 90)),
      sinceMs = 0,
    ).segments
    assertEquals(
      listOf(ActivitySegment(Activities.STILL, 0, 60), ActivitySegment(Activities.CYCLING, 60, 90)),
      segments,
    )
  }

  @Test
  fun repeatedEnterKeepsTheEarlierStartAndOrphanExitIsIgnored() {
    val segments = TransitionPairer.pair(
      listOf(exit(Activities.WALKING, 5), enter(Activities.STILL, 10), enter(Activities.STILL, 40), exit(Activities.STILL, 70)),
      sinceMs = 0,
    ).segments
    assertEquals(listOf(ActivitySegment(Activities.STILL, 10, 70)), segments)
  }

  @Test
  fun exitBeforeEnterAtTheSameInstantAndOrderDoesNotMatter() {
    val transitions = listOf(
      enter(Activities.WALKING, 0), exit(Activities.WALKING, 50), enter(Activities.STILL, 50), exit(Activities.STILL, 80),
    )
    val expected = TransitionPairer.pair(transitions, sinceMs = 0)
    assertEquals(expected, TransitionPairer.pair((transitions + transitions).reversed(), sinceMs = 0))
    assertEquals(
      listOf(ActivitySegment(Activities.WALKING, 0, 50), ActivitySegment(Activities.STILL, 50, 80)),
      expected.segments,
    )
  }

  @Test
  fun zeroLengthSegmentsAreDropped() {
    val segments = TransitionPairer.pair(listOf(enter(Activities.WALKING, 10), exit(Activities.WALKING, 10)), 0).segments
    assertTrue(segments.isEmpty())
  }

  @Test
  fun segmentsEndedBeforeSinceAreTrimmed() {
    val transitions = listOf(
      enter(Activities.STILL, 0), exit(Activities.STILL, 100),
      enter(Activities.WALKING, 100), exit(Activities.WALKING, 300),
      enter(Activities.STILL, 300),
    )
    val result = TransitionPairer.pair(transitions, sinceMs = 200)
    assertEquals(listOf(ActivitySegment(Activities.WALKING, 100, 300)), result.segments)
    assertEquals(100L, result.trimBeforeMs)

    val trimmed = transitions.filter { it.timeMs >= result.trimBeforeMs }
    assertEquals(result.segments, TransitionPairer.pair(trimmed, sinceMs = 200).segments)
  }
}
