package dev.context.app.collect.usage

import android.content.Context
import dev.context.app.collect.Collector
import dev.context.core.model.Event

/** Scaffold stub: reports itself as not ready, so the runner skips it and keeps no cursor. */
class UsageCollector(private val context: Context) : Collector {
  override val key = "usage"

  override suspend fun missingPermissions(): List<String> = listOf("collector not implemented yet")

  override suspend fun collect(fromMs: Long, toMs: Long): List<Event> = emptyList()
}
