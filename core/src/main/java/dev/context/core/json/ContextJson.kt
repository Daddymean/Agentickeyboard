package dev.context.core.json

import dev.context.core.model.Event
import dev.context.core.model.Snapshot
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The single JSON configuration for every string that crosses the AIDL
 * boundary. Both ends must use it so defaults and nulls encode identically.
 *
 * - `ignoreUnknownKeys`: a newer service can add fields without breaking an
 *   older keyboard build.
 * - `encodeDefaults` + `explicitNulls`: every contract key is always present,
 *   so non-Kotlin consumers (Edge Functions) see the documented shape.
 */
object ContextJson {
  val json: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = true
  }

  private val eventList = ListSerializer(Event.serializer())

  fun encodeEvent(event: Event): String = json.encodeToString(Event.serializer(), event)

  fun decodeEvent(text: String): Event = json.decodeFromString(Event.serializer(), text)

  fun encodeEvents(events: List<Event>): String = json.encodeToString(eventList, events)

  /** @throws IllegalArgumentException when [text] is not a JSON array of Event. */
  fun decodeEvents(text: String): List<Event> = json.decodeFromString(eventList, text)

  fun encodeSnapshot(snapshot: Snapshot): String = json.encodeToString(Snapshot.serializer(), snapshot)

  fun decodeSnapshot(text: String): Snapshot = json.decodeFromString(Snapshot.serializer(), text)

  /** Joins already-encoded Event objects into a JSON array without re-encoding. */
  fun joinArray(encodedEvents: List<String>): String {
    val sb = StringBuilder(arrayLength(encodedEvents.sumOf { it.length }, encodedEvents.size))
    sb.append('[')
    encodedEvents.forEachIndexed { i, e ->
      if (i > 0) sb.append(',')
      sb.append(e)
    }
    return sb.append(']').toString()
  }

  /** Length of the array [joinArray] builds from [count] items totalling [itemChars]. */
  fun arrayLength(itemChars: Int, count: Int): Int = 2 + itemChars + maxOf(0, count - 1)
}
