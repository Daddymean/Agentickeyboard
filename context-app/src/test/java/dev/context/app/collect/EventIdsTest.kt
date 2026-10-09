package dev.context.app.collect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EventIdsTest {
  @Test
  fun sameInputsGiveTheSameId() {
    assertEquals(EventIds.stable("health.steps", 1L, "a"), EventIds.stable("health.steps", 1L, "a"))
  }

  @Test
  fun typePartsAndNullsAllMatter() {
    val base = EventIds.stable("health.steps", 1L)
    assertNotEquals(base, EventIds.stable("health.sleep", 1L))
    assertNotEquals(base, EventIds.stable("health.steps", 2L))
    assertNotEquals(EventIds.stable("t", null), EventIds.stable("t", "null"))
    // Part boundaries are not ambiguous.
    assertNotEquals(EventIds.stable("t", "ab", "c"), EventIds.stable("t", "a", "bc"))
  }
}
