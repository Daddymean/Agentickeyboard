package dev.context.app.collect.location

import android.content.Context
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Small on-device buffer between the location receivers and [LocationCollector].
 *
 * The receivers only append raw [Fix]es and [Transition]s (one JSON object per
 * line); the collector reads them, derives closed events and trims what it has
 * fully processed. Lives in `noBackupFilesDir`, so raw whereabouts never reach
 * Auto Backup. Each file is capped at [maxBytes]: past that, the oldest half
 * is dropped. All access is serialized on one process-wide lock, and rewrites
 * go through a temp file so a crash can't leave a half-trimmed buffer.
 * Unparseable lines (e.g. a torn last append) are skipped.
 */
class LocationBuffer(private val dir: File, private val maxBytes: Long = DEFAULT_MAX_BYTES) {
  private val fixesFile = File(dir, "fixes.jsonl")
  private val transitionsFile = File(dir, "transitions.jsonl")

  fun appendFixes(fixes: List<Fix>) = append(fixesFile, Fix.serializer(), fixes)

  fun appendTransitions(transitions: List<Transition>) = append(transitionsFile, Transition.serializer(), transitions)

  fun readFixes(): List<Fix> = read(fixesFile, Fix.serializer())

  fun readTransitions(): List<Transition> = read(transitionsFile, Transition.serializer())

  /** Drops fixes with `timeMs < beforeMs`. */
  fun trimFixes(beforeMs: Long) = retain(fixesFile, Fix.serializer()) { it.timeMs >= beforeMs }

  /** Drops transitions with `timeMs < beforeMs`. */
  fun trimTransitions(beforeMs: Long) = retain(transitionsFile, Transition.serializer()) { it.timeMs >= beforeMs }

  private fun <T> append(file: File, serializer: KSerializer<T>, items: List<T>) {
    if (items.isEmpty()) return
    val text = items.joinToString(separator = "\n", postfix = "\n") { json.encodeToString(serializer, it) }
    synchronized(LOCK) {
      dir.mkdirs()
      file.appendText(text)
      if (file.length() > maxBytes) {
        val lines = file.readLines().filter { it.isNotBlank() }
        writeAtomically(file, lines.drop(lines.size / 2))
      }
    }
  }

  private fun <T> read(file: File, serializer: KSerializer<T>): List<T> {
    val lines = synchronized(LOCK) { if (file.exists()) file.readLines() else emptyList() }
    return lines.mapNotNull { decode(serializer, it) }
  }

  private fun <T> retain(file: File, serializer: KSerializer<T>, keep: (T) -> Boolean) {
    synchronized(LOCK) {
      if (!file.exists()) return
      val lines = file.readLines()
      val kept = lines.filter { line -> decode(serializer, line)?.let(keep) ?: false }
      if (kept.size != lines.size) writeAtomically(file, kept)
    }
  }

  private fun <T> decode(serializer: KSerializer<T>, line: String): T? {
    if (line.isBlank()) return null
    return try {
      json.decodeFromString(serializer, line)
    } catch (e: IllegalArgumentException) { // SerializationException is one
      null
    }
  }

  private fun writeAtomically(file: File, lines: List<String>) {
    val tmp = File(dir, "${file.name}.tmp")
    tmp.writeText(if (lines.isEmpty()) "" else lines.joinToString(separator = "\n", postfix = "\n"))
    if (!tmp.renameTo(file)) {
      file.writeText(tmp.readText())
      tmp.delete()
    }
  }

  companion object {
    /** ~25 K fixes at ~80 bytes each: weeks of balanced-power updates. */
    const val DEFAULT_MAX_BYTES = 2L * 1024 * 1024

    private val LOCK = Any()
    private val json = Json { ignoreUnknownKeys = true }

    /** The app's buffer, under `noBackupFilesDir/location/`. */
    fun forContext(context: Context) = LocationBuffer(File(context.applicationContext.noBackupFilesDir, "location"))
  }
}
