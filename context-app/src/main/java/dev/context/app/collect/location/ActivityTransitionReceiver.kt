package dev.context.app.collect.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity

/**
 * Receives activity transitions (registered by
 * [LocationCollector.ensureRegistered]) and appends them to the
 * [LocationBuffer]. [LocationCollector.collect] pairs them into closed
 * `location.activity` segments on the next pipeline run.
 */
class ActivityTransitionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (!ActivityTransitionResult.hasResult(intent)) return
    val events = ActivityTransitionResult.extractResult(intent)?.transitionEvents.orEmpty()
    // Transition times are elapsed-realtime; convert to wall clock once per batch.
    val bootWallMs = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    val transitions = events.mapNotNull { event ->
      val activity = ACTIVITY_NAMES[event.activityType] ?: return@mapNotNull null
      Transition(
        timeMs = bootWallMs + event.elapsedRealTimeNanos / 1_000_000,
        activity = activity,
        enter = event.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER,
      )
    }
    if (transitions.isEmpty()) return
    val buffer = LocationBuffer.forContext(context)
    runAsync { buffer.appendTransitions(transitions) }
  }

  companion object {
    /** The activity types [LocationCollector] subscribes to, mapped to payload names. */
    internal val ACTIVITY_NAMES: Map<Int, String> = mapOf(
      DetectedActivity.WALKING to Activities.WALKING,
      DetectedActivity.RUNNING to Activities.RUNNING,
      DetectedActivity.ON_BICYCLE to Activities.CYCLING,
      DetectedActivity.IN_VEHICLE to Activities.IN_VEHICLE,
      DetectedActivity.STILL to Activities.STILL,
    )
  }
}
