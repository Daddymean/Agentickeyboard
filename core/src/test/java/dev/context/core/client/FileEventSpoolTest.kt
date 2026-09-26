package dev.context.core.client

import dev.context.core.json.ContextJson
import dev.context.core.testing.testEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileEventSpoolTest {
  @get:Rule val tmp = TemporaryFolder()

  private fun encoded(vararg n: Int) = n.map { ContextJson.encodeEvent(testEvent(it)) }

  @Test
  fun appendPeekRemoveInFifoOrder() {
    val spool = FileEventSpool(tmp.root.resolve("spool.jsonl"))
    assertTrue(spool.isEmpty())
    assertEquals(3, spool.append(encoded(1, 2, 3)))
    assertFalse(spool.isEmpty())

    val first = spool.peek(maxChars = Int.MAX_VALUE, maxEvents = 2)
    assertEquals(encoded(1, 2), first.events)
    assertEquals(2, first.consumed)
    spool.remove(first.consumed)

    assertEquals(encoded(3), spool.peek(Int.MAX_VALUE, 10).events)
    spool.remove(1)
    assertTrue(spool.isEmpty())
  }

  @Test
  fun respectsCharBudgetButAlwaysReturnsOne() {
    val spool = FileEventSpool(tmp.root.resolve("spool.jsonl"))
    val items = encoded(1, 2)
    spool.append(items)
    assertEquals(1, spool.peek(maxChars = 1, maxEvents = 10).events.size)
    assertEquals(2, spool.peek(maxChars = ContextJson.arrayLength(items.sumOf { it.length }, 2), maxEvents = 10).events.size)
  }

  @Test
  fun stopsAppendingAtSizeCap() {
    val items = encoded(1, 2, 3)
    val cap = items.take(2).sumOf { it.toByteArray().size + 1 }.toLong()
    val spool = FileEventSpool(tmp.root.resolve("spool.jsonl"), maxBytes = cap)
    assertEquals(2, spool.append(items))
  }

  @Test
  fun skipsCorruptLinesButCountsThemAsConsumed() {
    val file = tmp.root.resolve("spool.jsonl")
    file.writeText(encoded(1).single() + "\n{\"torn\n" + encoded(2).single() + "\n")
    val spool = FileEventSpool(file)
    val batch = spool.peek(Int.MAX_VALUE, 10)
    assertEquals(encoded(1, 2), batch.events)
    assertEquals(3, batch.consumed)
    spool.remove(batch.consumed)
    assertTrue(spool.isEmpty())
  }

  @Test
  fun survivesReopen() {
    val file = tmp.root.resolve("spool.jsonl")
    FileEventSpool(file).append(encoded(7))
    assertEquals(encoded(7), FileEventSpool(file).peek(Int.MAX_VALUE, 10).events)
  }
}
