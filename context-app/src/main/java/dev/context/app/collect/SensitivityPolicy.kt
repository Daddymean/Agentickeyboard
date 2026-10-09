package dev.context.app.collect

import dev.context.core.model.EventTypes
import dev.context.core.model.Sensitivity

/**
 * Default sensitivity per event type. Privacy-first: anything that reveals the
 * body or precise whereabouts stays on the device unless this policy changes.
 * Only levels <= [Sensitivity.SYNC_MAX] can reach Supabase.
 */
object SensitivityPolicy {
  fun forType(type: String): Int = when (type) {
    EventTypes.HEALTH_SLEEP, EventTypes.HEALTH_HEART_RATE, EventTypes.HEALTH_STEPS -> Sensitivity.PRIVATE
    EventTypes.LOCATION_VISIT -> Sensitivity.PRIVATE
    EventTypes.LOCATION_ACTIVITY -> Sensitivity.PERSONAL
    EventTypes.USAGE_SESSION -> Sensitivity.PERSONAL
    EventTypes.CALENDAR_EVENT -> Sensitivity.PERSONAL
    // kb.note carries the keyboard user's own choice; unknown types fail closed.
    else -> Sensitivity.PRIVATE
  }
}
