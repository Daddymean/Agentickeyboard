package io.github.daddymean.agentickeyboard.util

import android.content.Context

/** Only app consent is persisted, never captured messages. */
class ConversationCapturePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("conversation_capture", Context.MODE_PRIVATE)

    fun isAllowed(packageName: String): Boolean =
        packageName in prefs.getStringSet("allowed_apps", emptySet()).orEmpty()

    /**
     * KEYBOARD-022: read the mood of the latest incoming message when the keyboard
     * opens in an allowed app. On by default (Keith, 2026-10-10 02:00 PT: the badge
     * appears automatically); the per-app opt-in still gates it, and it can be turned off.
     */
    var isAutoMoodEnabled: Boolean
        get() = prefs.getBoolean("auto_mood", true)
        set(value) { prefs.edit().putBoolean("auto_mood", value).apply() }

    fun setAllowed(packageName: String, allowed: Boolean) {
        val apps = prefs.getStringSet("allowed_apps", emptySet()).orEmpty().toMutableSet()
        if (allowed) apps.add(packageName) else apps.remove(packageName)
        prefs.edit().putStringSet("allowed_apps", apps).apply()
    }
}
