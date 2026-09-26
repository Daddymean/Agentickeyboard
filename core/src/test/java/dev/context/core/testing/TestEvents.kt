package dev.context.core.testing

import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import dev.context.core.model.Sensitivity

/** Deterministic Event factory shared by the :core unit tests. */
fun testEvent(
  n: Int,
  type: String = EventTypes.USAGE_SESSION,
  startMs: Long = 1_000L * n,
  endMs: Long? = null,
  payload: String = """{"n":$n}""",
  sensitivity: Int = Sensitivity.PERSONAL,
): Event = Event(
  id = "evt-%05d".format(n),
  type = type,
  startMs = startMs,
  endMs = endMs,
  source = "test",
  payload = payload,
  sensitivity = sensitivity,
  createdMs = startMs,
)
