package dev.context.app.collect.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import com.google.android.gms.location.LocationResult

/**
 * Receives batched fused-location results (registered by
 * [LocationCollector.ensureRegistered]) and appends them to the
 * [LocationBuffer]. Never writes events: [LocationCollector.collect] derives
 * closed visits from the buffer on the next pipeline run.
 */
class LocationUpdatesReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!LocationResult.hasResult(intent)) return
    val fixes = LocationResult.extractResult(intent)?.locations.orEmpty().map { it.toFix() }
    if (fixes.isEmpty()) return
    val buffer = LocationBuffer.forContext(context)
    runAsync { buffer.appendFixes(fixes) }
  }

  private fun Location.toFix() = Fix(
    timeMs = time,
    lat = latitude,
    lng = longitude,
    accuracyM = if (hasAccuracy()) accuracy else Fix.UNKNOWN_ACCURACY_M,
  )
}
