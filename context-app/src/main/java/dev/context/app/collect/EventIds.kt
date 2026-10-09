package dev.context.app.collect

import java.util.UUID

/**
 * Deterministic event ids. Re-collecting the same underlying record must yield
 * the same id so `INSERT OR IGNORE` dedupes it; a random UUID would duplicate it.
 */
object EventIds {
  private const val SEPARATOR = '\u001F'

  /** Name-based (v3) UUID over [type] and [parts]; nulls are encoded distinctly from "null". */
  fun stable(type: String, vararg parts: Any?): String {
    val name = buildString {
      append(type)
      for (part in parts) {
        append(SEPARATOR)
        append(part?.toString() ?: "\u0000")
      }
    }
    return UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
  }
}
