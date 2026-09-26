package dev.context.core.client

import dev.context.core.json.ContextJson
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Durable overflow for events the batcher could not deliver. Only touched on
 * the failure path, so the happy path never does disk I/O. Items are encoded
 * Event JSON objects, oldest first.
 */
interface EventSpool {
  /** Appends [encodedEvents]; returns how many were accepted (fewer when full). */
  @Throws(IOException::class)
  fun append(encodedEvents: List<String>): Int

  /** Oldest items whose JSON array fits [maxChars] and [maxEvents]. */
  @Throws(IOException::class)
  fun peek(maxChars: Int, maxEvents: Int): Batch

  /** Removes the first [count] entries (as reported by [Batch.consumed]). */
  @Throws(IOException::class)
  fun remove(count: Int)

  fun isEmpty(): Boolean

  /**
   * [events] are the deliverable items; [consumed] also counts corrupt entries
   * skipped along the way, so `remove(consumed)` discards those too.
   */
  class Batch(val events: List<String>, val consumed: Int)
}

/**
 * JSON-lines spool in the client app's private storage. Encoded events never
 * contain raw newlines (JSON escapes them), so one line is one event. A torn
 * final line from a crash mid-append is detected on [peek] and discarded.
 */
class FileEventSpool(
  private val file: File,
  private val maxBytes: Long = 4L * 1024 * 1024,
) : EventSpool {
  private val lock = Any()

  override fun append(encodedEvents: List<String>): Int = synchronized(lock) {
    if (encodedEvents.isEmpty()) return 0
    file.parentFile?.mkdirs()
    var size = if (file.exists()) file.length() else 0L
    var accepted = 0
    FileOutputStream(file, /* append = */ true).bufferedWriter(Charsets.UTF_8).use { out ->
      for (event in encodedEvents) {
        val bytes = event.toByteArray(Charsets.UTF_8).size + 1L
        if (size + bytes > maxBytes) break
        out.write(event)
        out.write('\n'.code)
        size += bytes
        accepted++
      }
    }
    accepted
  }

  override fun peek(maxChars: Int, maxEvents: Int): EventSpool.Batch = synchronized(lock) {
    if (!file.exists()) return EventSpool.Batch(emptyList(), 0)
    val events = ArrayList<String>()
    var consumed = 0
    var chars = 0
    file.bufferedReader(Charsets.UTF_8).useLines { lines ->
      for (line in lines) {
        if (events.size >= maxEvents) break
        if (line.isBlank() || !isEvent(line)) {
          consumed++
          continue
        }
        if (events.isNotEmpty() && ContextJson.arrayLength(chars + line.length, events.size + 1) > maxChars) break
        events += line
        chars += line.length
        consumed++
      }
    }
    EventSpool.Batch(events, consumed)
  }

  override fun remove(count: Int): Unit = synchronized(lock) {
    if (count <= 0 || !file.exists()) return
    val remaining = file.bufferedReader(Charsets.UTF_8).useLines { it.drop(count).toList() }
    if (remaining.isEmpty()) {
      file.delete()
      return
    }
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.bufferedWriter(Charsets.UTF_8).use { out ->
      remaining.forEach {
        out.write(it)
        out.write('\n'.code)
      }
    }
    if (!tmp.renameTo(file)) throw IOException("could not replace spool $file")
  }

  override fun isEmpty(): Boolean = synchronized(lock) { !file.exists() || file.length() == 0L }

  private fun isEvent(line: String): Boolean =
    try {
      ContextJson.decodeEvent(line)
      true
    } catch (_: Exception) {
      false
    }
}

/** Non-durable spool for tests and callers that opt out of disk writes. */
class InMemoryEventSpool(private val maxEvents: Int = 10_000) : EventSpool {
  private val items = ArrayDeque<String>()

  @Synchronized
  override fun append(encodedEvents: List<String>): Int {
    val room = (maxEvents - items.size).coerceAtLeast(0)
    val accepted = encodedEvents.take(room)
    items.addAll(accepted)
    return accepted.size
  }

  @Synchronized
  override fun peek(maxChars: Int, maxEvents: Int): EventSpool.Batch {
    val out = ArrayList<String>()
    var chars = 0
    for (item in items) {
      if (out.size >= maxEvents) break
      if (out.isNotEmpty() && ContextJson.arrayLength(chars + item.length, out.size + 1) > maxChars) break
      out += item
      chars += item.length
    }
    return EventSpool.Batch(out, out.size)
  }

  @Synchronized
  override fun remove(count: Int) {
    repeat(minOf(count, items.size)) { items.removeFirst() }
  }

  @Synchronized
  override fun isEmpty(): Boolean = items.isEmpty()

  @Synchronized
  fun snapshot(): List<String> = items.toList()
}
