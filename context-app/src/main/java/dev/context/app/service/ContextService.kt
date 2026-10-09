package dev.context.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.context.IContextService
import dev.context.app.graph
import dev.context.app.work.Schedules

/**
 * IContextService host. Binding is guarded by the signature permission declared
 * in the manifest, so only apps signed with this app's key get here.
 *
 * Binder threads are already background threads, so the blocking Room calls
 * below are fine; nothing here computes a snapshot (it is served as published).
 */
class ContextService : Service() {
  private val binder = object : IContextService.Stub() {
    override fun getSnapshot(): String = graph.snapshots.localJson()

    override fun logEvents(eventsJson: String?) {
      requireNotNull(eventsJson) { "eventsJson must not be null" }
      // New notes should reach recentNotes soon; the request coalesces bursts.
      if (graph.store.logEventsJson(eventsJson) > 0) Schedules.requestDistill(this@ContextService)
    }

    override fun queryRange(fromMs: Long, toMs: Long, types: Array<out String>?): String =
      graph.store.queryRangeJson(fromMs, toMs, types)
  }

  override fun onBind(intent: Intent): IBinder = binder
}
