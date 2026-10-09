package dev.context.app.collect.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.os.SystemClock
import com.google.android.gms.location.ActivityRecognition
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionRequest
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.Task
import dev.context.app.collect.Collector
import dev.context.app.collect.EventIds
import dev.context.app.collect.SensitivityPolicy
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

/**
 * `location.visit` and `location.activity`, battery first: no foreground
 * service and no polling.
 *
 * [ensureRegistered] asks the fused provider for balanced-power fixes every
 * ~15 min, batched for up to an hour, and subscribes to activity transitions.
 * Both are delivered to manifest receivers ([LocationUpdatesReceiver],
 * [ActivityTransitionReceiver]) that only append to a [LocationBuffer].
 * [collect] turns the buffer into **closed** events ([StayDetector],
 * [TransitionPairer]) and trims what it no longer needs; an ongoing visit or
 * activity is emitted only once it has ended.
 *
 * Visits stay on the device ([SensitivityPolicy]: PRIVATE). Their label comes
 * from the platform [Geocoder], which may use a network backend, so it is only
 * asked about a ~100 m cell (coordinates rounded to 3 decimals), once per new
 * visit; the label is null when that fails or times out (events are immutable,
 * so it is not retried).
 *
 * Precise location is required: approximate fixes (~2 km) are all rejected by
 * [StayDetector.MAX_ACCURACY_M], so coarse-only would silently yield no visits.
 */
class LocationCollector internal constructor(
  private val context: Context,
  private val buffer: LocationBuffer,
  private val geocode: suspend (lat: Double, lng: Double) -> String?,
  private val clock: () -> Long,
) : Collector {
  constructor(context: Context) : this(
    context.applicationContext,
    LocationBuffer.forContext(context),
    { lat, lng -> reverseGeocode(context.applicationContext, lat, lng) },
    System::currentTimeMillis,
  )

  override val key = "location"

  override suspend fun missingPermissions(): List<String> = buildList {
    // Android only grants FINE together with COARSE, so ask for both.
    if (!isGranted(Manifest.permission.ACCESS_FINE_LOCATION)) add(Manifest.permission.ACCESS_FINE_LOCATION)
    if (!isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) add(Manifest.permission.ACCESS_COARSE_LOCATION)
    if (!isGranted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    if (!isGranted(Manifest.permission.ACTIVITY_RECOGNITION)) add(Manifest.permission.ACTIVITY_RECOGNITION)
  }

  /**
   * (Re-)registers both passive subscriptions. Re-registering with the same
   * PendingIntent replaces the previous request, so this is idempotent; it is
   * skipped when this process already registered within
   * [REREGISTER_INTERVAL_MS] (a reboot or app update starts a new process,
   * which re-registers on its first run).
   */
  override suspend fun ensureRegistered() {
    val now = SystemClock.elapsedRealtime()
    val last = lastRegisteredElapsedMs
    if (last != 0L && now - last < REREGISTER_INTERVAL_MS) return
    requestLocationUpdates()
    requestActivityTransitions()
    lastRegisteredElapsedMs = now
  }

  override suspend fun collect(fromMs: Long, toMs: Long): List<Event> = withContext(Dispatchers.IO) {
    val nowMs = clock()
    // Re-derive a margin before the runner's window so a closing fix or EXIT
    // that arrived late (batching, Doze) still yields its event; ids are stable.
    val sinceMs = fromMs - REPROCESS_MARGIN_MS
    val visits = StayDetector.detect(buffer.readFixes(), sinceMs)
    val activities = TransitionPairer.pair(buffer.readTransitions(), sinceMs)

    // Stays that closed before fromMs were (almost always) stored by an earlier
    // run; INSERT OR IGNORE would discard a fresh label, so don't geocode them.
    val events = visits.stays.map { visitEvent(it, nowMs, lookUpLabel = it.closedAtMs >= fromMs) } +
      activities.segments.map { activityEvent(it, nowMs) }

    // Trimming only drops data from before sinceMs, so a failed insert loses
    // nothing: the next run re-reads the same window. The age cap bounds an
    // open stay or segment that never closes.
    val floorMs = nowMs - MAX_BUFFER_AGE_MS
    buffer.trimFixes(maxOf(visits.trimBeforeMs, floorMs))
    buffer.trimTransitions(maxOf(activities.trimBeforeMs, floorMs))
    events
  }

  private suspend fun visitEvent(stay: Stay, nowMs: Long, lookUpLabel: Boolean): Event {
    val lat3 = round(stay.lat, 3)
    val lng3 = round(stay.lng, 3)
    val label = if (lookUpLabel) labelFor(lat3, lng3) else null
    val payload = buildJsonObject {
      put("lat", round(stay.lat, 4))
      put("lng", round(stay.lng, 4))
      put("radiusM", stay.radiusM)
      put("label", label)
    }
    return Event(
      id = EventIds.stable(EventTypes.LOCATION_VISIT, stay.startMs, lat3, lng3),
      type = EventTypes.LOCATION_VISIT,
      startMs = stay.startMs,
      endMs = stay.endMs,
      source = SOURCE_LOCATION,
      payload = payload.toString(),
      sensitivity = SensitivityPolicy.forType(EventTypes.LOCATION_VISIT),
      createdMs = nowMs,
    )
  }

  private fun activityEvent(segment: ActivitySegment, nowMs: Long): Event = Event(
    id = EventIds.stable(EventTypes.LOCATION_ACTIVITY, segment.activity, segment.startMs),
    type = EventTypes.LOCATION_ACTIVITY,
    startMs = segment.startMs,
    endMs = segment.endMs,
    source = SOURCE_ACTIVITY,
    payload = buildJsonObject { put("activity", segment.activity) }.toString(),
    sensitivity = SensitivityPolicy.forType(EventTypes.LOCATION_ACTIVITY),
    createdMs = nowMs,
  )

  /** Geocodes the ~100 m cell, cached per cell so repeat visits don't geocode again. */
  private suspend fun labelFor(lat3: Double, lng3: Double): String? {
    val cellKey = "$lat3,$lng3"
    labelCache[cellKey]?.let { return it }
    val label = geocode(lat3, lng3) ?: return null
    if (labelCache.size >= LABEL_CACHE_SIZE) labelCache.clear()
    labelCache[cellKey] = label
    return label
  }

  @SuppressLint("MissingPermission") // Checked immediately above each call.
  private suspend fun requestLocationUpdates() {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
      context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
    ) {
      return
    }
    val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, UPDATE_INTERVAL_MS)
      .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
      .setMaxUpdateDelayMillis(MAX_UPDATE_DELAY_MS)
      .build()
    LocationServices.getFusedLocationProviderClient(context)
      .requestLocationUpdates(request, pendingIntent(LocationUpdatesReceiver::class.java, REQUEST_LOCATION))
      .awaitCompletion()
  }

  @SuppressLint("MissingPermission") // Checked immediately above the call.
  private suspend fun requestActivityTransitions() {
    if (context.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
      return
    }
    val transitions = ActivityTransitionReceiver.ACTIVITY_NAMES.keys.flatMap { type ->
      listOf(ActivityTransition.ACTIVITY_TRANSITION_ENTER, ActivityTransition.ACTIVITY_TRANSITION_EXIT).map { transition ->
        ActivityTransition.Builder().setActivityType(type).setActivityTransition(transition).build()
      }
    }
    ActivityRecognition.getClient(context)
      .requestActivityTransitionUpdates(
        ActivityTransitionRequest(transitions),
        pendingIntent(ActivityTransitionReceiver::class.java, REQUEST_ACTIVITY),
      )
      .awaitCompletion()
  }

  /** Explicit, mutable broadcast: Play services fills the result into the intent's extras. */
  private fun pendingIntent(receiver: Class<*>, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
    context,
    requestCode,
    Intent(context, receiver),
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
  )

  private fun isGranted(permission: String) =
    context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

  companion object {
    const val SOURCE_LOCATION = "fused_location"
    const val SOURCE_ACTIVITY = "activity_recognition"

    /** Everything this collector needs; background location must be requested after the foreground ones. */
    val PERMISSIONS: List<String> = listOf(
      Manifest.permission.ACCESS_FINE_LOCATION,
      Manifest.permission.ACCESS_COARSE_LOCATION,
      Manifest.permission.ACCESS_BACKGROUND_LOCATION,
      Manifest.permission.ACTIVITY_RECOGNITION,
    )

    const val UPDATE_INTERVAL_MS = 15L * 60 * 1000
    const val MIN_UPDATE_INTERVAL_MS = 5L * 60 * 1000
    const val MAX_UPDATE_DELAY_MS = 60L * 60 * 1000
    const val REREGISTER_INTERVAL_MS = 6L * 60 * 60 * 1000
    const val REPROCESS_MARGIN_MS = 6L * 60 * 60 * 1000
    const val MAX_BUFFER_AGE_MS = 7L * 24 * 60 * 60 * 1000
    const val GEOCODE_TIMEOUT_MS = 5_000L

    private const val REQUEST_LOCATION = 1
    private const val REQUEST_ACTIVITY = 2
    private const val LABEL_CACHE_SIZE = 256

    @Volatile private var lastRegisteredElapsedMs = 0L
    private val labelCache = ConcurrentHashMap<String, String>()

    private fun round(value: Double, decimals: Int): Double {
      val scale = Math.pow(10.0, decimals.toDouble())
      return (value * scale).roundToLong() / scale
    }

    /** One-line address for ([lat], [lng]), or null when unavailable, failing or slow. */
    private suspend fun reverseGeocode(context: Context, lat: Double, lng: Double): String? {
      if (!Geocoder.isPresent()) return null
      return withTimeoutOrNull(GEOCODE_TIMEOUT_MS) {
        suspendCancellableCoroutine<String?> { cont ->
          val listener = object : Geocoder.GeocodeListener {
            override fun onGeocode(addresses: MutableList<Address>) {
              if (cont.isActive) cont.resume(addresses.firstOrNull()?.let(::labelOf))
            }

            override fun onError(errorMessage: String?) {
              if (cont.isActive) cont.resume(null)
            }
          }
          try {
            Geocoder(context).getFromLocation(lat, lng, 1, listener)
          } catch (e: IllegalArgumentException) {
            if (cont.isActive) cont.resume(null)
          }
        }
      }
    }

    private fun labelOf(address: Address): String? =
      (address.getAddressLine(0) ?: address.featureName)?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL_CHARS)

    private const val MAX_LABEL_CHARS = 160

    private suspend fun <T> Task<T>.awaitCompletion() = suspendCancellableCoroutine<Unit> { cont ->
      addOnCompleteListener { task ->
        val error = task.exception
        when {
          error != null -> cont.resumeWithException(error)
          task.isCanceled -> cont.cancel()
          else -> cont.resume(Unit)
        }
      }
    }
  }
}
