package dev.context.app.collect.usage

import android.app.usage.UsageEvents

/**
 * Pure pairing and merging of usage transitions into foreground sessions. No
 * Android calls, so the rules are unit-tested on the JVM.
 *
 * Rules:
 * - Spans are tracked per package. [Kind.OPEN] starts one unless the package is
 *   already open (a repeated resume keeps the earliest start); [Kind.CLOSE] ends
 *   it; [Kind.CLOSE_ALL] (screen off, shutdown) ends every open span.
 * - A close with no matching open (its resume was before the window) is ignored.
 * - Spans still open after the last transition are dropped: events are
 *   immutable, so only closed spans are emitted. The next run's overlap
 *   re-read picks them up once they close.
 * - Spans of the same package separated by less than [MERGE_GAP_MS] (activity
 *   to activity inside one app, a brief overlay) are merged; overlapping spans
 *   of one package (multi-window) merge too.
 * - After merging, spans shorter than [MIN_DURATION_MS] are dropped.
 * - Transitions for [excluded] packages are ignored entirely.
 */
object UsageSessions {
  const val MERGE_GAP_MS = 2_000L
  const val MIN_DURATION_MS = 5_000L

  enum class Kind { OPEN, CLOSE, CLOSE_ALL }

  /** One usage transition. [packageName] is ignored for [Kind.CLOSE_ALL]. */
  data class Transition(val timeMs: Long, val packageName: String, val kind: Kind)

  /** A closed foreground span of one package, `[startMs, endMs]`. */
  data class Span(val packageName: String, val startMs: Long, val endMs: Long)

  /** Maps a `UsageEvents.Event.eventType` to a [Kind], or null for event types that don't matter. */
  fun kindOf(eventType: Int): Kind? = when (eventType) {
    UsageEvents.Event.ACTIVITY_RESUMED -> Kind.OPEN
    UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> Kind.CLOSE
    UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> Kind.CLOSE_ALL
    else -> null
  }

  /** Closed, merged sessions of at least [minDurationMs], sorted by `(startMs, packageName)`. */
  fun build(
    transitions: List<Transition>,
    excluded: Set<String> = emptySet(),
    mergeGapMs: Long = MERGE_GAP_MS,
    minDurationMs: Long = MIN_DURATION_MS,
  ): List<Span> {
    val open = LinkedHashMap<String, Long>()
    val closed = mutableListOf<Span>()
    // sortedBy is stable: same-millisecond transitions keep their source order.
    for (t in transitions.sortedBy { it.timeMs }) {
      when (t.kind) {
        Kind.OPEN -> if (t.packageName !in excluded) open.putIfAbsent(t.packageName, t.timeMs)
        Kind.CLOSE -> open.remove(t.packageName)?.let { closed += Span(t.packageName, it, t.timeMs) }
        Kind.CLOSE_ALL -> {
          for ((pkg, start) in open) closed += Span(pkg, start, t.timeMs)
          open.clear()
        }
      }
    }
    return closed
      .groupBy { it.packageName }
      .flatMap { (_, spans) -> merge(spans.sortedBy { it.startMs }, mergeGapMs) }
      .filter { it.endMs - it.startMs >= minDurationMs }
      .sortedWith(compareBy<Span> { it.startMs }.thenBy { it.packageName })
  }

  private fun merge(sorted: List<Span>, gapMs: Long): List<Span> {
    val out = mutableListOf<Span>()
    for (span in sorted) {
      val last = out.lastOrNull()
      if (last != null && span.startMs - last.endMs < gapMs) {
        out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, span.endMs))
      } else {
        out += span
      }
    }
    return out
  }
}
