package dev.context.core.model

import dev.context.core.ContextContract
import dev.context.core.json.ContextJson
import kotlinx.serialization.json.Json

/**
 * Structural checks applied on both sides of the IPC: the client rejects bad
 * events before queueing them (so one bad row can never poison a batch) and the
 * service re-checks because the binder is a trust boundary.
 */
object EventValidator {
  const val MAX_ID_CHARS = 64
  const val MAX_TYPE_CHARS = 64
  const val MAX_SOURCE_CHARS = 128

  /** Returns null when [event] is valid, otherwise a human-readable reason. */
  fun validate(event: Event, encoded: String = ContextJson.encodeEvent(event)): String? = when {
    event.id.isBlank() -> "blank id"
    event.id.length > MAX_ID_CHARS -> "id longer than $MAX_ID_CHARS"
    event.type.isBlank() -> "blank type"
    event.type.length > MAX_TYPE_CHARS -> "type longer than $MAX_TYPE_CHARS"
    event.source.isBlank() -> "blank source"
    event.source.length > MAX_SOURCE_CHARS -> "source longer than $MAX_SOURCE_CHARS"
    !Sensitivity.isValid(event.sensitivity) -> "sensitivity ${event.sensitivity} outside 0..3"
    event.startMs < 0 -> "negative startMs"
    event.endMs != null && event.endMs < event.startMs -> "endMs before startMs"
    encoded.length > ContextContract.MAX_EVENT_CHARS ->
      "encoded event is ${encoded.length} chars (max ${ContextContract.MAX_EVENT_CHARS})"
    !isJson(event.payload) -> "payload is not valid JSON"
    else -> null
  }

  private fun isJson(text: String): Boolean =
    try {
      Json.parseToJsonElement(text)
      true
    } catch (_: Exception) {
      false
    }
}
