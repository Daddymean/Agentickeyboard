package io.github.daddymean.agentickeyboard.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistent keyboard settings backed by SharedPreferences so preferences (most
 * importantly the offline privacy mode) survive process death and stay in sync
 * between the companion app and the IME service. Register a listener to observe
 * writes made by the other component.
 */
class KeyboardSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_OFFLINE_MODE = "offline_mode"
        const val KEY_SWIPE_ENABLED = "swipe_enabled"
        const val KEY_AUTO_CAPITALIZE = "auto_capitalize"
        const val KEY_NUMBER_ROW = "number_row"
        const val KEY_PROOFREAD = "proofread_as_you_type"
        const val KEY_LEARNING_PAUSED = "learning_paused"
        const val KEY_HAPTICS = "haptics_enabled"
        const val KEY_VOICE_LOCK = "voice_lock"
        const val KEY_SEND_GUARD = "send_guard"
        const val KEY_PERSONA = "persona"
        const val KEY_SOURCE_LANG = "source_lang"
        const val KEY_TARGET_LANG = "target_lang"
        const val KEY_LOG_RETENTION_DAYS = "log_retention_days"
        const val KEY_STAT_AUTOCORRECTIONS = "stat_autocorrections"
        const val KEY_STAT_SWIPE_WORDS = "stat_swipe_words"
        const val KEY_STAT_AI_APPLIES = "stat_ai_applies"
        const val KEY_STAT_SHORTCUT_EXPANSIONS = "stat_shortcut_expansions"
    }

    var isOfflineMode: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_OFFLINE_MODE, value).apply()

    var isSwipeEnabled: Boolean
        get() = prefs.getBoolean(KEY_SWIPE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_SWIPE_ENABLED, value).apply()

    var isAutoCapitalizeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPITALIZE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CAPITALIZE, value).apply()

    var isNumberRowEnabled: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, false)
        set(value) = prefs.edit().putBoolean(KEY_NUMBER_ROW, value).apply()

    /** Background grammar checking sends drafts to the cloud, so it is opt-in. */
    var isProofreadEnabled: Boolean
        get() = prefs.getBoolean(KEY_PROOFREAD, false)
        set(value) = prefs.edit().putBoolean(KEY_PROOFREAD, value).apply()

    var isLearningPaused: Boolean
        get() = prefs.getBoolean(KEY_LEARNING_PAUSED, false)
        set(value) = prefs.edit().putBoolean(KEY_LEARNING_PAUSED, value).apply()

    var isHapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTICS, value).apply()

    /** Voice-lock: AI rewrite/compose/continue must preserve the user's own phrasing. */
    var isVoiceLockEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_LOCK, false)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_LOCK, value).apply()

    /** Send-guard: pause the Send action once when a draft reads hostile. Opt-in. */
    var isSendGuardEnabled: Boolean
        get() = prefs.getBoolean(KEY_SEND_GUARD, false)
        set(value) = prefs.edit().putBoolean(KEY_SEND_GUARD, value).apply()

    var persona: String
        get() = prefs.getString(KEY_PERSONA, "Match my history") ?: "Match my history"
        set(value) = prefs.edit().putString(KEY_PERSONA, value).apply()

    var sourceLanguage: String
        get() = prefs.getString(KEY_SOURCE_LANG, "English") ?: "English"
        set(value) = prefs.edit().putString(KEY_SOURCE_LANG, value).apply()

    var targetLanguage: String
        get() = prefs.getString(KEY_TARGET_LANG, "Spanish") ?: "Spanish"
        set(value) = prefs.edit().putString(KEY_TARGET_LANG, value).apply()

    var logRetentionDays: Int
        get() = prefs.getInt(KEY_LOG_RETENTION_DAYS, 30)
        set(value) = prefs.edit().putInt(KEY_LOG_RETENTION_DAYS, value).apply()

    // --- Local usage statistics (never leave the device) ---

    var statAutoCorrections: Int
        get() = prefs.getInt(KEY_STAT_AUTOCORRECTIONS, 0)
        set(value) = prefs.edit().putInt(KEY_STAT_AUTOCORRECTIONS, value).apply()

    var statSwipeWords: Int
        get() = prefs.getInt(KEY_STAT_SWIPE_WORDS, 0)
        set(value) = prefs.edit().putInt(KEY_STAT_SWIPE_WORDS, value).apply()

    var statAiApplies: Int
        get() = prefs.getInt(KEY_STAT_AI_APPLIES, 0)
        set(value) = prefs.edit().putInt(KEY_STAT_AI_APPLIES, value).apply()

    var statShortcutExpansions: Int
        get() = prefs.getInt(KEY_STAT_SHORTCUT_EXPANSIONS, 0)
        set(value) = prefs.edit().putInt(KEY_STAT_SHORTCUT_EXPANSIONS, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
