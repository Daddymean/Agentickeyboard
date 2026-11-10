package io.github.daddymean.agentickeyboard.vscreen

import android.content.Context

/**
 * Stored companion settings. The feature is off until the user opts in
 * ([KEY_ENABLED] defaults to false). The pasted token is kept in a private
 * preference; a later change can wrap it with Android Keystore the way
 * GeminiKeyStore does. Do not log the token.
 */
class VscreenPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun token(): String? = VscreenToken.normalize(prefs.getString(KEY_TOKEN, null))

    fun setToken(raw: String?) {
        val token = VscreenToken.normalize(raw)
        val edit = prefs.edit()
        if (token == null) edit.remove(KEY_TOKEN) else edit.putString(KEY_TOKEN, token)
        edit.apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    fun host(): String = prefs.getString(KEY_HOST, VscreenEndpoint.DEFAULT_HOST)
        ?: VscreenEndpoint.DEFAULT_HOST

    fun port(): Int = prefs.getInt(KEY_PORT, VscreenEndpoint.DEFAULT_PORT)

    companion object {
        const val PREFS = "vscreen_companion"
        const val KEY_ENABLED = "enabled"
        const val KEY_TOKEN = "token"
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val DEFAULT_ENABLED = false
    }
}
