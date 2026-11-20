package io.github.daddymean.agentickeyboard.learned

import android.content.SharedPreferences
import androidx.room.withTransaction
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import io.github.daddymean.agentickeyboard.util.LearnedRuleCleanup
import io.github.daddymean.agentickeyboard.util.TouchCalibration
import java.text.DateFormat
import java.util.Date

/**
 * Every place the keyboard itself (not the legacy twin, see TwinLedger) keeps something it
 * learned from Keith's typing or use. KEYBOARD-047, follow-up to #230 item 9.
 */
enum class KeyboardStore(val title: String, val what: String) {
    WORDS("Learned words", "Words you type, counted, used for suggestions and to stop auto-fix changing them. Deleting a word also deletes its next-word pairs; clearing all words clears all next-word pairs."),
    WORD_PAIRS("Next-word pairs", "Which word you tend to type after another, used for next-word predictions."),
    CORRECTIONS("Auto-correct rules", "Typos you corrected and the word the keyboard now puts in their place."),
    REMOVED_RULES("Removed rules kept for Restore", "Rules the one-time cleanup removed (KEYBOARD-008), kept so Settings can put them back."),
    NEVER_AUTOFIX("Words auto-fix leaves alone", "Words you undid an auto-fix on twice. Deleting one lets auto-fix correct it again."),
    WRITING_LOG("Writing log", "Text you ran through grammar fix or tone check, with its tone, kept for the Style Hub and personal-model export. Also pruned by its retention setting."),
    APP_STYLES("Style per app", "The writing style you picked in each app, remembered for next time."),
    CLIPBOARD("Clipboard history", "Clips the keyboard kept after you turned clipboard history on, pinned ones too."),
    TOUCH_CALIBRATION("Touch calibration", "How far from each key's centre you tend to tap, per letter. No text.")
}

/** One entry on the screen. [key] identifies it for delete; [text] is shown, never logged. */
data class KeyboardEntry(val store: KeyboardStore, val key: String, val text: String, val detail: String)

/**
 * Lists, counts and deletes the keyboard's learned data across Room (`agentic_keyboard_database`)
 * and the two SharedPreferences files that hold learned state. Every Room delete runs in a
 * transaction with `secure_delete` on, then checkpoints the WAL so the deleted text doesn't wait
 * in `-wal` (see [scrubWal]). Nothing here logs, touches the network or the twin.
 *
 * **Residue** (documented in the PR): the keyboard database is not app-encrypted (Android's
 * file-based encryption still covers it at rest). `secure_delete` zeroes freed cells in the
 * database file and the TRUNCATE checkpoint empties `-wal`, but flash wear-levelling can keep old
 * blocks that only the device's own encryption protects. SharedPreferences are rewritten whole by
 * Android (old file renamed to `.bak`, then unlinked), so the previous XML can linger in free flash
 * blocks the same way. Must be called off the main thread.
 */
class KeyboardLearnedData(
    private val db: AppDatabase,
    private val settings: KeyboardSettings,
    private val cleanupPrefs: SharedPreferences
) {
    suspend fun counts(): Map<KeyboardStore, Int> = KeyboardStore.values().associateWith { count(it) }

    suspend fun count(store: KeyboardStore): Int = when (store) {
        KeyboardStore.WORDS -> db.userVocabularyDao().count()
        KeyboardStore.WORD_PAIRS -> db.wordBigramDao().count()
        KeyboardStore.CORRECTIONS -> db.learnedCorrectionDao().count()
        KeyboardStore.REMOVED_RULES -> LearnedRuleCleanup.backupCount(cleanupPrefs)
        KeyboardStore.NEVER_AUTOFIX -> settings.autoFixNeverWords.size
        KeyboardStore.WRITING_LOG -> db.writingLogDao().count()
        KeyboardStore.APP_STYLES -> db.appPersonaDao().count()
        KeyboardStore.CLIPBOARD -> db.clipboardHistoryDao().count()
        KeyboardStore.TOUCH_CALIBRATION -> TouchCalibration.letters(settings.touchCalibration).size
    }

    /** Up to [limit] entries, most used or newest first. */
    suspend fun entries(store: KeyboardStore, limit: Int): List<KeyboardEntry> = when (store) {
        KeyboardStore.WORDS -> db.userVocabularyDao().top(limit).map {
            KeyboardEntry(store, it.word, it.word, "typed ${it.count}×, last ${date(it.lastUsed)}")
        }
        KeyboardStore.WORD_PAIRS -> db.wordBigramDao().top(limit).map {
            KeyboardEntry(store, pairKey(it.firstWord, it.secondWord), "${it.firstWord} → ${it.secondWord}", "${it.count}×")
        }
        KeyboardStore.CORRECTIONS -> db.learnedCorrectionDao().getAllCorrectionsOnce()
            .sortedWith(compareByDescending<io.github.daddymean.agentickeyboard.db.LearnedCorrection> { it.count }.thenBy { it.typo })
            .take(limit).map { KeyboardEntry(store, it.id.toString(), "${it.typo} → ${it.correction}", "learned ${it.count}×") }
        KeyboardStore.REMOVED_RULES -> LearnedRuleCleanup.backupEntries(cleanupPrefs).take(limit).map {
            KeyboardEntry(store, it.typo, "${it.typo} → ${it.correction}", "removed by the cleanup")
        }
        KeyboardStore.NEVER_AUTOFIX -> settings.autoFixNeverWords.sorted().take(limit).map {
            KeyboardEntry(store, it, it, "never auto-fixed")
        }
        KeyboardStore.WRITING_LOG -> db.writingLogDao().recent(limit).map {
            KeyboardEntry(store, it.id.toString(), it.originalText, "${date(it.timestamp)} · ${it.sentiment} · ${it.wordCount} words")
        }
        KeyboardStore.APP_STYLES -> db.appPersonaDao().getAllOnce().take(limit).map {
            val app = it.appLabel.ifBlank { it.packageName }
            KeyboardEntry(store, it.packageName, "$app: ${it.persona}", it.packageName)
        }
        KeyboardStore.CLIPBOARD -> db.clipboardHistoryDao().getAllNow().take(limit).map {
            KeyboardEntry(store, it.id.toString(), it.content, (if (it.pinned) "pinned · " else "") + "copied ${date(it.lastSeenAt)}")
        }
        KeyboardStore.TOUCH_CALIBRATION -> TouchCalibration.letters(settings.touchCalibration).take(limit).map { (letter, samples) ->
            KeyboardEntry(store, letter.toString(), "Key ${letter.uppercaseChar()}", "$samples taps learned")
        }
    }

    /** Deletes one entry; returns how many records went (0 if it was already gone). */
    suspend fun delete(entry: KeyboardEntry): Int {
        val n = when (entry.store) {
            KeyboardStore.WORDS -> secureTx { db.userVocabularyDao().deleteWord(entry.key) + db.wordBigramDao().deleteWithWord(entry.key) }
            KeyboardStore.WORD_PAIRS -> {
                val (a, b) = splitPair(entry.key) ?: return 0
                secureTx { db.wordBigramDao().delete(a, b) }
            }
            KeyboardStore.CORRECTIONS -> secureTx {
                val id = entry.key.toIntOrNull() ?: return@secureTx 0
                val before = db.learnedCorrectionDao().count()
                db.learnedCorrectionDao().deleteById(id)
                before - db.learnedCorrectionDao().count()
            }
            KeyboardStore.REMOVED_RULES -> LearnedRuleCleanup.forgetBackupEntry(cleanupPrefs, entry.key)
            KeyboardStore.NEVER_AUTOFIX -> {
                val now = settings.autoFixNeverWords
                if (entry.key !in now) 0 else { settings.autoFixNeverWords = now - entry.key; 1 }
            }
            KeyboardStore.WRITING_LOG -> secureTx { entry.key.toIntOrNull()?.let { db.writingLogDao().deleteById(it) } ?: 0 }
            KeyboardStore.APP_STYLES -> secureTx {
                val before = db.appPersonaDao().count()
                db.appPersonaDao().delete(entry.key)
                before - db.appPersonaDao().count()
            }
            KeyboardStore.CLIPBOARD -> secureTx {
                val id = entry.key.toIntOrNull() ?: return@secureTx 0
                val before = db.clipboardHistoryDao().count()
                db.clipboardHistoryDao().deleteById(id)
                before - db.clipboardHistoryDao().count()
            }
            KeyboardStore.TOUCH_CALIBRATION -> {
                val letter = entry.key.singleOrNull() ?: return 0
                val now = settings.touchCalibration
                val kept = TouchCalibration.withoutLetter(now, letter)
                if (kept == TouchCalibration.normalize(now)) 0 else { settings.touchCalibration = kept; 1 }
            }
        }
        if (n > 0 && entry.store.inRoom) scrubWal()
        return n
    }

    /** Clears one store; returns how many records went. */
    suspend fun clear(store: KeyboardStore): Int {
        val n = clearNoScrub(store)
        if (n > 0 && store.inRoom) scrubWal()
        return n
    }

    /** Clears every store; returns what each lost. Room stores go in one transaction. */
    suspend fun clearAll(): Map<KeyboardStore, Int> {
        val room = secureTx { KeyboardStore.values().filter { it.inRoom }.associateWith { clearNoScrub(it, inTx = true) } }
        val other = KeyboardStore.values().filter { !it.inRoom }.associateWith { clearNoScrub(it) }
        if (room.values.any { it > 0 }) scrubWal()
        return KeyboardStore.values().associateWith { room[it] ?: other[it] ?: 0 }
    }

    private suspend fun clearNoScrub(store: KeyboardStore, inTx: Boolean = false): Int {
        suspend fun room(block: suspend () -> Int) = if (inTx) block() else secureTx { block() }
        return when (store) {
            // Every next-word pair is made of learned words, so clearing words clears the pairs too
            // (#231 review blocker 2), like deleting one word drops its pairs.
            KeyboardStore.WORDS -> room {
                val words = db.userVocabularyDao().count().also { db.userVocabularyDao().clearAll() }
                words + db.wordBigramDao().count().also { db.wordBigramDao().clearAll() }
            }
            KeyboardStore.WORD_PAIRS -> room { db.wordBigramDao().count().also { db.wordBigramDao().clearAll() } }
            KeyboardStore.CORRECTIONS -> room { db.learnedCorrectionDao().count().also { db.learnedCorrectionDao().clearAll() } }
            KeyboardStore.WRITING_LOG -> room { db.writingLogDao().count().also { db.writingLogDao().clearAll() } }
            KeyboardStore.APP_STYLES -> room { db.appPersonaDao().clearAll() }
            KeyboardStore.CLIPBOARD -> room { db.clipboardHistoryDao().count().also { db.clipboardHistoryDao().clearAll() } }
            KeyboardStore.REMOVED_RULES -> LearnedRuleCleanup.clearBackup(cleanupPrefs)
            KeyboardStore.NEVER_AUTOFIX -> settings.autoFixNeverWords.size.also { if (it > 0) settings.autoFixNeverWords = emptySet() }
            KeyboardStore.TOUCH_CALIBRATION -> TouchCalibration.letters(settings.touchCalibration).size.also {
                if (it > 0 || settings.touchCalibration.isNotEmpty()) settings.touchCalibration = ""
            }
        }
    }

    /** Runs [block] in one transaction with `secure_delete` on for the writer connection. */
    private suspend fun <T> secureTx(block: suspend () -> T): T = db.withTransaction {
        db.openHelper.writableDatabase.query("PRAGMA secure_delete = ON").use { it.moveToFirst() }
        block()
    }

    /**
     * Copies the WAL into the database and truncates it to zero bytes, so deleted text isn't left
     * in `-wal` frames. Best effort: if a reader holds the WAL it reports busy and SQLite's own
     * auto-checkpoint finishes the job later. Returns true when the WAL is empty (or not in use).
     */
    suspend fun scrubWal(): Boolean = runCatching {
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c ->
            !c.moveToFirst() || c.getInt(0) == 0
        }
    }.getOrDefault(false)

    private fun date(millis: Long): String =
        if (millis <= 0) "unknown" else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    companion object {
        private const val SEP = '\u0000'
        fun pairKey(first: String, second: String) = "$first$SEP$second"
        fun splitPair(key: String): Pair<String, String>? =
            key.split(SEP).takeIf { it.size == 2 }?.let { it[0] to it[1] }
    }
}

/** Stores kept in the Room database (the rest are SharedPreferences). */
val KeyboardStore.inRoom: Boolean
    get() = this != KeyboardStore.REMOVED_RULES && this != KeyboardStore.NEVER_AUTOFIX && this != KeyboardStore.TOUCH_CALIBRATION
