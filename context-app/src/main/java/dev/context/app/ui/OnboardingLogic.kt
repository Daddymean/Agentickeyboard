package dev.context.app.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Pure helpers behind the onboarding screen: how to grant what a collector
 * reports as missing, and how to format statuses. No Android types, so they
 * are covered by plain JVM tests.
 */

/** The next thing the user can do to unblock a collector. */
sealed interface GrantStep {
  /** Ask Health Connect for the health permissions. */
  data object Health : GrantStep

  /** Ask for ordinary runtime permissions in one system dialog. */
  data class Runtime(val permissions: List<String>) : GrantStep

  /**
   * Ask for background location on its own. Android only offers it once
   * foreground location is granted, and in a separate request (on Android 11+
   * it opens the "Allow all the time" settings page instead of a dialog).
   */
  data object BackgroundLocation : GrantStep

  /** Open a Settings screen, e.g. `android.settings.USAGE_ACCESS_SETTINGS`. */
  data class OpenSettings(val action: String) : GrantStep
}

const val PERMISSION_PREFIX = "android.permission."
const val HEALTH_PERMISSION_PREFIX = "android.permission.health."
const val SETTINGS_ACTION_PREFIX = "android.settings."
const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"

/** Health Connect asks the app to explain its health access with these actions. */
const val ACTION_HEALTH_RATIONALE = "androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"
const val ACTION_VIEW_PERMISSION_USAGE = "android.intent.action.VIEW_PERMISSION_USAGE"

/** True when the activity was opened to explain health data use. */
fun isHealthRationaleAction(action: String?): Boolean =
  action == ACTION_HEALTH_RATIONALE || action == ACTION_VIEW_PERMISSION_USAGE

/** Whether [item] is something a button can grant (as opposed to plain text). */
fun isActionable(item: String): Boolean =
  item.startsWith(PERMISSION_PREFIX) || item.startsWith(SETTINGS_ACTION_PREFIX)

/**
 * The step that unblocks the most of [missing], or null when nothing in it can
 * be granted from the app (only human-readable reasons are left). Order:
 * Health Connect, foreground runtime permissions, background location (which
 * Android only grants after foreground location), then Settings screens.
 */
fun nextGrantStep(missing: List<String>): GrantStep? {
  if (missing.any { it.startsWith(HEALTH_PERMISSION_PREFIX) }) return GrantStep.Health
  val runtime = missing.filter {
    it.startsWith(PERMISSION_PREFIX) && !it.startsWith(HEALTH_PERMISSION_PREFIX) && it != BACKGROUND_LOCATION
  }
  if (runtime.isNotEmpty()) return GrantStep.Runtime(runtime.distinct())
  if (BACKGROUND_LOCATION in missing) return GrantStep.BackgroundLocation
  missing.firstOrNull { it.startsWith(SETTINGS_ACTION_PREFIX) }?.let { return GrantStep.OpenSettings(it) }
  return null
}

/** Short label for one missing item, e.g. `ACCESS_FINE_LOCATION` or `Usage access`. */
fun describeMissing(item: String): String = when {
  item == "android.settings.USAGE_ACCESS_SETTINGS" -> "Usage access"
  item.startsWith(HEALTH_PERMISSION_PREFIX) -> "Health: " + item.removePrefix(HEALTH_PERMISSION_PREFIX)
  item.startsWith(PERMISSION_PREFIX) -> item.removePrefix(PERMISSION_PREFIX)
  item.startsWith(SETTINGS_ACTION_PREFIX) -> "Settings: " + item.removePrefix(SETTINGS_ACTION_PREFIX)
  else -> item
}

/** Label for the grant button of [step]. */
fun grantLabel(step: GrantStep): String = when (step) {
  GrantStep.Health -> "Grant in Health Connect"
  is GrantStep.Runtime -> "Grant"
  GrantStep.BackgroundLocation -> "Allow all the time"
  is GrantStep.OpenSettings -> "Open settings"
}

/** "never run", or the status with how long ago it was recorded. */
fun formatStatus(status: String?, atMs: Long, nowMs: Long): String {
  if (status.isNullOrBlank()) return "never run"
  if (atMs <= 0L) return status
  return "$status · ${formatAgo(atMs, nowMs)}"
}

/** Coarse relative time: "just now", "5 min ago", "3 h ago", "2 d ago". */
fun formatAgo(atMs: Long, nowMs: Long): String {
  val minutes = (nowMs - atMs).coerceAtLeast(0L) / 60_000L
  return when {
    minutes < 1 -> "just now"
    minutes < 60 -> "$minutes min ago"
    minutes < 48 * 60 -> "${minutes / 60} h ago"
    else -> "${minutes / (24 * 60)} d ago"
  }
}

private val prettyPrinter = Json { prettyPrint = true }

/** [raw] re-indented for reading, or unchanged when it is not valid JSON. */
fun prettyJson(raw: String): String =
  runCatching { prettyPrinter.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(raw)) }
    .getOrDefault(raw)
