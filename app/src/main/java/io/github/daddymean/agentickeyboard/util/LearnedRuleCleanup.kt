package io.github.daddymean.agentickeyboard.util

import android.content.SharedPreferences
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import org.json.JSONArray
import org.json.JSONObject

/**
 * KEYBOARD-008: a one-time, reversible cleanup of learned rules saved before the
 * fix. It removes rules that rewrite a real word (`there → their`) or produce a
 * non-word (`your → youre`). Removed rules are kept in an app-private backup, so
 * Settings can put them back. It runs only once the dictionary has loaded.
 */
object LearnedRuleCleanup {
    const val PREFS_NAME = "learned_rule_cleanup"
    private const val KEY_DONE = "v1_done"
    private const val KEY_BACKUP = "v1_backup"

    /** Removes harmful rules once; returns how many were removed (0 if already done). */
    suspend fun runOnce(prefs: SharedPreferences, repository: KeyboardRepository, isWord: (String) -> Boolean): Int {
        if (prefs.getBoolean(KEY_DONE, false)) return 0
        val harmful = repository.getAllCorrectionsOnce()
            .filter { LearnedRuleFilter.isHarmful(it.typo, it.correction, isWord) }
        // The backup is written (and committed) before anything is deleted.
        val backup = JSONArray()
        harmful.forEach {
            backup.put(JSONObject().put("typo", it.typo).put("correction", it.correction).put("count", it.count))
        }
        prefs.edit().putString(KEY_BACKUP, backup.toString()).commit()
        harmful.forEach { repository.deleteCorrectionById(it.id) }
        prefs.edit().putBoolean(KEY_DONE, true).commit()
        return harmful.size
    }

    /** How many removed rules can be restored. */
    fun backupCount(prefs: SharedPreferences): Int = readBackup(prefs).size

    /** Puts the removed rules back (unless the typo has a rule again) and empties the backup. */
    suspend fun restore(prefs: SharedPreferences, repository: KeyboardRepository): Int {
        val rules = readBackup(prefs)
        if (rules.isEmpty()) return 0
        val existing = repository.getCorrectionsForTypos(rules.map { it.typo }).map { it.typo }.toSet()
        val toInsert = rules.filter { it.typo !in existing }
        repository.insertCorrections(toInsert)
        prefs.edit().putString(KEY_BACKUP, "[]").commit()
        return toInsert.size
    }

    private fun readBackup(prefs: SharedPreferences): List<LearnedCorrection> {
        val json = prefs.getString(KEY_BACKUP, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                LearnedCorrection(typo = o.getString("typo"), correction = o.getString("correction"), count = o.optInt("count", 1))
            }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }
}
