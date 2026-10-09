package dev.context.app.collect.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocationBufferTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun fix(t: Long) = Fix(t, 52.52, 13.405, 15f)

  @Test
  fun appendsAndReadsBack() {
    val buffer = LocationBuffer(File(tmp.root, "location"))
    assertTrue(buffer.readFixes().isEmpty())
    buffer.appendFixes(listOf(fix(1), fix(2)))
    buffer.appendFixes(listOf(fix(3)))
    buffer.appendTransitions(listOf(Transition(5, Activities.WALKING, enter = true)))
    assertEquals(listOf(fix(1), fix(2), fix(3)), buffer.readFixes())
    assertEquals(listOf(Transition(5, Activities.WALKING, enter = true)), buffer.readTransitions())
  }

  @Test
  fun trimDropsOnlyOlderEntries() {
    val buffer = LocationBuffer(File(tmp.root, "location"))
    buffer.appendFixes((1L..5L).map(::fix))
    buffer.appendTransitions(listOf(Transition(1, Activities.STILL, true), Transition(9, Activities.STILL, false)))
    buffer.trimFixes(3)
    buffer.trimTransitions(5)
    assertEquals((3L..5L).map(::fix), buffer.readFixes())
    assertEquals(listOf(Transition(9, Activities.STILL, false)), buffer.readTransitions())
    // Appends keep working after a rewrite.
    buffer.appendFixes(listOf(fix(6)))
    assertEquals((3L..6L).map(::fix), buffer.readFixes())
  }

  @Test
  fun corruptLinesAreSkipped() {
    val dir = File(tmp.root, "location")
    val buffer = LocationBuffer(dir)
    buffer.appendFixes(listOf(fix(1)))
    File(dir, "fixes.jsonl").appendText("{\"timeMs\":2,\"la\n")
    buffer.appendFixes(listOf(fix(3)))
    assertEquals(listOf(fix(1), fix(3)), buffer.readFixes())
  }

  @Test
  fun sizeIsBoundedByDroppingTheOldestHalf() {
    val buffer = LocationBuffer(File(tmp.root, "location"), maxBytes = 2_000)
    repeat(100) { buffer.appendFixes(listOf(fix(it.toLong()))) }
    val fixes = buffer.readFixes()
    assertTrue(File(tmp.root, "location/fixes.jsonl").length() <= 2_000)
    assertTrue(fixes.size in 10 until 100)
    // The newest fixes survive.
    assertEquals(99L, fixes.last().timeMs)
    assertEquals(fixes.sortedBy { it.timeMs }, fixes)
  }
}
