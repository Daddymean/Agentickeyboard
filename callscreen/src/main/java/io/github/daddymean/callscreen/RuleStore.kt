package io.github.daddymean.callscreen

import android.content.Context

/** Only explicit user settings, never call events, numbers from calls, or history. */
class RuleStore(context: Context) {
    private val prefs = context.getSharedPreferences("screening_rules", Context.MODE_PRIVATE)
    var active: Boolean
        get() = prefs.getBoolean("active", false)
        set(value) { prefs.edit().putBoolean("active", value).apply() }
    var unknownAction: Action
        get() = runCatching { Action.valueOf(prefs.getString("action", "ALLOW") ?: "ALLOW") }
            .getOrDefault(Action.ALLOW)
        set(value) { prefs.edit().putString("action", value.name).apply() }
    var allowlist: Set<String>
        get() = prefs.getStringSet("allowlist", emptySet())?.toSet() ?: emptySet()
        set(value) { prefs.edit().putStringSet("allowlist", value.toSet()).apply() }
    fun activate(action: Action) {
        prefs.edit().putString("action", action.name).putBoolean("active", true).apply()
    }
}
