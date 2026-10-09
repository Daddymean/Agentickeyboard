package dev.context.app.settings

import android.content.Context

/**
 * Small persistent key/value store for configuration, cursors and statuses.
 * App-private SharedPreferences; the app disables backup, so the sync token
 * never leaves the device through Auto Backup.
 *
 * Key namespaces: `cursor.<collector>`, `status.<collector>` and
 * `statusAt.<collector>` (see [cursorKey] etc.), `sync.*` for the sync client.
 */
class SettingsStore(context: Context) {
  private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

  /** Project URL such as `https://abcd.supabase.co`, without a trailing slash. */
  var supabaseUrl: String
    get() = prefs.getString(KEY_URL, "").orEmpty()
    set(value) = prefs.edit().putString(KEY_URL, value.trim().trimEnd('/')).apply()

  /** Bearer secret the `sync` Edge Function checks (its `SYNC_TOKEN`). */
  var syncToken: String
    get() = prefs.getString(KEY_TOKEN, "").orEmpty()
    set(value) = prefs.edit().putString(KEY_TOKEN, value.trim()).apply()

  val isSyncConfigured: Boolean
    get() = supabaseUrl.startsWith("https://") && syncToken.isNotBlank()

  fun getLong(key: String, default: Long): Long = prefs.getLong(key, default)

  fun putLong(key: String, value: Long) = prefs.edit().putLong(key, value).apply()

  fun getString(key: String): String? = prefs.getString(key, null)

  /** Stores [value], or removes the key when it is null. */
  fun putString(key: String, value: String?) =
    prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()

  companion object {
    private const val FILE = "context_settings"
    private const val KEY_URL = "supabase.url"
    private const val KEY_TOKEN = "supabase.syncToken"

    fun cursorKey(collector: String) = "cursor.$collector"

    fun statusKey(component: String) = "status.$component"

    fun statusAtKey(component: String) = "statusAt.$component"
  }
}
