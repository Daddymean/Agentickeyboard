package dev.context.core.client

import dev.context.core.ContextContract
import dev.context.core.json.ContextJson
import dev.context.core.model.Event
import dev.context.core.testing.testEvent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeQueryPagerTest {
  /** Mimics ContextStore.queryRangeJson's truncation over an in-memory list. */
  private fun fakeService(all: List<Event>): suspend (Long, Long, Array<String>) -> String = { from, to, _ ->
    val sb = StringBuilder("[")
    var count = 0
    for (e in all.filter { it.startMs in from until to }.sortedWith(compareBy({ it.startMs }, { it.id }))) {
      val encoded = ContextJson.encodeEvent(e)
      val sep = if (count > 0) 1 else 0
      if (sb.length + sep + encoded.length + 1 > ContextContract.MAX_REPLY_CHARS) break
      if (sep == 1) sb.append(',')
      sb.append(encoded)
      count++
    }
    sb.append(']').toString()
  }

  private val bulky = """{"t":"${"x".repeat(9_000)}"}"""

  @Test
  fun smallReplyIsOnePage() = runTest {
    var calls = 0
    val service = fakeService((1..10).map { testEvent(it) })
    val pager = RangeQueryPager { f, t, ty -> calls++; service(f, t, ty) }
    assertEquals(10, pager.fetch(0, Long.MAX_VALUE, emptyArray()).size)
    assertEquals(1, calls)
  }

  @Test
  fun truncatedRepliesArePagedAndDeduplicated() = runTest {
    val all = (1..60).map { testEvent(it, payload = bulky) }
    var calls = 0
    val service = fakeService(all)
    val pager = RangeQueryPager { f, t, ty -> calls++; service(f, t, ty) }
    val result = pager.fetch(0, Long.MAX_VALUE, emptyArray())
    assertEquals(all.map { it.id }, result.map { it.id })
    assertTrue("expected several pages, got $calls", calls > 3)
  }

  @Test
  fun stopsWhenAPageAddsNothingNew() = runTest {
    // More same-startMs events than fit in one reply: cursor cannot advance.
    val all = (1..60).map { testEvent(it, startMs = 5_000, payload = bulky) }
    var calls = 0
    val service = fakeService(all)
    val pager = RangeQueryPager { f, t, ty -> calls++; service(f, t, ty) }
    val result = pager.fetch(0, Long.MAX_VALUE, emptyArray())
    assertTrue(result.isNotEmpty())
    assertEquals(2, calls)
  }

  @Test
  fun emptyRangeSkipsTheService() = runTest {
    val pager = RangeQueryPager { _, _, _ -> error("must not be called") }
    assertTrue(pager.fetch(10, 10, emptyArray()).isEmpty())
  }

  @Test
  fun truncationThresholdIsConsistentWithBudgets() {
    assertFalse(ContextContract.replyMayBeTruncated(2))
    assertTrue(ContextContract.replyMayBeTruncated(ContextContract.MAX_REPLY_CHARS))
    assertTrue(ContextContract.replyMayBeTruncated(ContextContract.MAX_REPLY_CHARS - ContextContract.MAX_EVENT_CHARS))
  }
}
