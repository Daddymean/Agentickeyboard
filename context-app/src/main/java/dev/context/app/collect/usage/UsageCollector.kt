package dev.context.app.collect.usage

import android.Manifest
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import dev.context.app.collect.Collector
import dev.context.app.collect.EventIds
import dev.context.app.collect.SensitivityPolicy
import dev.context.core.model.Event
import dev.context.core.model.EventTypes
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `usage.session` events: one per closed foreground span of an app, built from
 * `UsageStatsManager.queryEvents` by [UsageSessions]. Payload:
 * `{"package": String, "label": String}`.
 *
 * Excluded: this app, the home launcher(s), System UI and every enabled input
 * method, which are "foreground" around everything else and say nothing about
 * what the user was doing.
 *
 * Limitation: `queryEvents` only returns transitions inside the window, so a
 * session whose resume happened before `fromMs` has no start and is dropped.
 * The runner's [Collector.overlapMs] re-read (2 h) covers sessions that were
 * still open at the previous run; one that began more than the overlap before
 * the previous run and closed after it is lost. Sessions still open at `toMs`
 * are not emitted until a later run sees them close.
 */
class UsageCollector(private val context: Context) : Collector {
  override val key = "usage"

  /**
   * Usage access is a special app-op granted in system Settings, not a runtime
   * permission. When it is missing this returns
   * [Settings.ACTION_USAGE_ACCESS_SETTINGS]: by convention a missing entry that
   * is a Settings intent action is opened by the UI with `Intent(action)`.
   */
  override suspend fun missingPermissions(): List<String> =
    if (hasUsageAccess()) emptyList() else listOf(Settings.ACTION_USAGE_ACCESS_SETTINGS)

  override suspend fun collect(fromMs: Long, toMs: Long): List<Event> {
    val usm = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
    val transitions = mutableListOf<UsageSessions.Transition>()
    val events = usm.queryEvents(fromMs, toMs) ?: return emptyList()
    val event = UsageEvents.Event()
    while (events.hasNextEvent()) {
      if (!events.getNextEvent(event)) break
      val kind = UsageSessions.kindOf(event.eventType) ?: continue
      transitions += UsageSessions.Transition(event.timeStamp, event.packageName.orEmpty(), kind)
    }
    val spans = UsageSessions.build(transitions, excludedPackages())
    if (spans.isEmpty()) return emptyList()

    val nowMs = System.currentTimeMillis()
    val sensitivity = SensitivityPolicy.forType(EventTypes.USAGE_SESSION)
    val labels = HashMap<String, String>()
    return spans.map { span ->
      val label = labels.getOrPut(span.packageName) { labelOf(span.packageName) }
      Event(
        id = EventIds.stable(EventTypes.USAGE_SESSION, span.packageName, span.startMs),
        type = EventTypes.USAGE_SESSION,
        startMs = span.startMs,
        endMs = span.endMs,
        source = SOURCE,
        payload = buildJsonObject {
          put("package", span.packageName)
          put("label", label)
        }.toString(),
        sensitivity = sensitivity,
        createdMs = nowMs,
      )
    }
  }

  private fun hasUsageAccess(): Boolean {
    val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
    return when (
      appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
    ) {
      AppOpsManager.MODE_ALLOWED -> true
      // MODE_DEFAULT defers to the (appop) permission itself.
      AppOpsManager.MODE_DEFAULT ->
        context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
      else -> false
    }
  }

  private fun excludedPackages(): Set<String> = buildSet {
    add(context.packageName)
    add(SYSTEM_UI)
    val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    runCatching {
      context.packageManager
        .queryIntentActivities(home, PackageManager.ResolveInfoFlags.of(0L))
        .forEach { add(it.activityInfo.packageName) }
    }
    // Settings registers a fallback HOME activity for boot; it is a real app otherwise.
    remove(SETTINGS)
    runCatching {
      context.getSystemService(InputMethodManager::class.java)
        ?.enabledInputMethodList
        ?.forEach { add(it.packageName) }
    }
  }

  private fun labelOf(packageName: String): String = try {
    val pm = context.packageManager
    val info = pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0L))
    pm.getApplicationLabel(info).toString().ifBlank { packageName }
  } catch (_: PackageManager.NameNotFoundException) {
    packageName
  }

  private companion object {
    const val SOURCE = "usage_stats"
    const val SYSTEM_UI = "com.android.systemui"
    const val SETTINGS = "com.android.settings"
  }
}
