package io.github.daddymean.agentickeyboard.util

import android.content.Context

/** Only app consent is persisted, never captured messages. */
class ConversationCapturePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("conversation_capture", Context.MODE_PRIVATE)

    fun isAllowed(packageName: String): Boolean =
        packageName in prefs.getStringSet("allowed_apps", emptySet()).orEmpty()

    fun setAllowed(packageName: String, allowed: Boolean) {
        val apps = prefs.getStringSet("allowed_apps", emptySet()).orEmpty().toMutableSet()
        if (allowed) apps.add(packageName) else apps.remove(packageName)
        prefs.edit().putStringSet("allowed_apps", apps).apply()
    }
}
