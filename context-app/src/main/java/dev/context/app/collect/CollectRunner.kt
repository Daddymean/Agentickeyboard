package dev.context.app.collect

import dev.context.app.settings.SettingsStore
import dev.context.core.db.ContextDatabase
import dev.context.core.model.EventValidator
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs each [Collector] over the window since its last successful run and
 * stores the result. One collector failing (or lacking permissions) never
 * stops the others, and its cursor only advances after its events are stored.
 */
class CollectRunner(
  private val db: ContextDatabase,
  private val settings: SettingsStore,
  private val collectors: List<Collector>,
) {
  data class Outcome(
    val key: String,
    val inserted: Int = 0,
    val rejected: Int = 0,
    val skipped: String? = null,
    val error: Throwable? = null,
  ) {
    /** One line for the onboarding screen. */
    val summary: String
      get() = when {
        error != null -> "error: ${error.javaClass.simpleName}: ${error.message.orEmpty()}"
        skipped != null -> "skipped: $skipped"
        else -> "ok: +$inserted events" + if (rejected > 0) " ($rejected rejected)" else ""
      }
  }

  suspend fun runAll(nowMs: Long): List<Outcome> = collectors.map { runOne(it, nowMs) }

  suspend fun runOne(collector: Collector, nowMs: Long): Outcome {
    val outcome = try {
      val missing = collector.missingPermissions()
      if (missing.isNotEmpty()) {
        Outcome(collector.key, skipped = "needs ${missing.joinToString()}")
      } else {
        collector.ensureRegistered()
        val cursorKey = SettingsStore.cursorKey(collector.key)
        val cursor = settings.getLong(cursorKey, nowMs - DEFAULT_LOOKBACK_MS)
        val from = (cursor - collector.overlapMs).coerceAtLeast(0)
        val (valid, invalid) = collector.collect(from, nowMs).partition { EventValidator.validate(it) == null }
        val inserted = if (valid.isEmpty()) 0 else db.events().insertAll(valid).count { it != -1L }
        settings.putLong(cursorKey, nowMs)
        Outcome(collector.key, inserted = inserted, rejected = invalid.size)
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Outcome(collector.key, error = e)
    }
    settings.putString(SettingsStore.statusKey(collector.key), outcome.summary)
    settings.putLong(SettingsStore.statusAtKey(collector.key), nowMs)
    return outcome
  }

  companion object {
    /** How far back a collector's first ever run reaches. */
    const val DEFAULT_LOOKBACK_MS = 24L * 60 * 60 * 1000
  }
}
