package com.example.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.util.PrivacyTextSanitizer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

@Entity(tableName = "shortcut_templates")
data class ShortcutTemplate(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val shortcut: String,
    val template: String
)

@Entity(tableName = "writing_logs")
data class WritingLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val originalText: String,
    val sentiment: String,
    val toneScore: Float,
    val wordCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "learned_corrections")
data class LearnedCorrection(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val typo: String,
    val correction: String,
    val count: Int = 1
)

@Entity(tableName = "user_vocabulary")
data class UserVocabulary(
    @PrimaryKey val word: String,
    val count: Int = 1,
    val lastUsed: Long = System.currentTimeMillis()
)

@Entity(tableName = "app_settings")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String
)

@Dao
interface ShortcutDao {
    @Query("SELECT * FROM shortcut_templates ORDER BY shortcut ASC")
    fun getAllShortcuts(): Flow<List<ShortcutTemplate>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShortcut(shortcut: ShortcutTemplate)

    @Delete
    suspend fun deleteShortcut(shortcut: ShortcutTemplate)

    @Query("DELETE FROM shortcut_templates WHERE id = :id")
    suspend fun deleteById(id: Int)
}

@Dao
interface WritingLogDao {
    @Query("SELECT * FROM writing_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<WritingLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: WritingLog)

    @Query("DELETE FROM writing_logs WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM writing_logs WHERE id NOT IN (SELECT id FROM writing_logs ORDER BY timestamp DESC LIMIT :maxRows)")
    suspend fun keepNewest(maxRows: Int)

    @Query("DELETE FROM writing_logs")
    suspend fun clearAll()
}

@Dao
interface LearnedCorrectionDao {
    @Query("SELECT * FROM learned_corrections ORDER BY count DESC")
    fun getAllCorrections(): Flow<List<LearnedCorrection>>

    @Query("SELECT * FROM learned_corrections WHERE typo = :typo LIMIT 1")
    suspend fun getCorrectionForTypo(typo: String): LearnedCorrection?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCorrection(correction: LearnedCorrection)

    @Query("DELETE FROM learned_corrections WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM learned_corrections")
    suspend fun clearAll()
}

@Dao
interface UserVocabularyDao {
    @Query("SELECT * FROM user_vocabulary ORDER BY count DESC LIMIT 150")
    fun getTopVocabulary(): Flow<List<UserVocabulary>>

    @Query("SELECT * FROM user_vocabulary WHERE word = :word LIMIT 1")
    suspend fun getWord(word: String): UserVocabulary?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWord(word: UserVocabulary)

    @Query("UPDATE user_vocabulary SET count = count + 1, lastUsed = :now WHERE word = :word")
    suspend fun incrementWordCount(word: String, now: Long): Int

    @Query("DELETE FROM user_vocabulary")
    suspend fun clearAll()
}

@Dao
interface AppSettingDao {
    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    fun observeValue(key: String): Flow<String?>

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun getValue(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: AppSetting)
}

@Database(
    entities = [
        ShortcutTemplate::class,
        WritingLog::class,
        LearnedCorrection::class,
        UserVocabulary::class,
        AppSetting::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shortcutDao(): ShortcutDao
    abstract fun writingLogDao(): WritingLogDao
    abstract fun learnedCorrectionDao(): LearnedCorrectionDao
    abstract fun userVocabularyDao(): UserVocabularyDao
    abstract fun appSettingDao(): AppSettingDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agentic_keyboard_database"
                )
                .addMigrations(MIGRATION_2_3)
                // Version 1 shipped without schema export in this prototype. Preserve
                // known v2+ users, but recover safely from unknown v1 test installs.
                .fallbackToDestructiveMigrationFrom(1)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class KeyboardRepository(private val db: AppDatabase) {
    private companion object {
        const val KEY_OFFLINE_MODE = "offline_mode"
        const val MAX_WRITING_LOGS = 250
        const val MAX_LOG_TEXT_CHARS = 500
        val LOG_RETENTION_MILLIS: Long = TimeUnit.DAYS.toMillis(30)
    }

    val allShortcuts: Flow<List<ShortcutTemplate>> = db.shortcutDao().getAllShortcuts()
    val allLogs: Flow<List<WritingLog>> = db.writingLogDao().getAllLogs()
    val allCorrections: Flow<List<LearnedCorrection>> = db.learnedCorrectionDao().getAllCorrections()
    val topVocabulary: Flow<List<UserVocabulary>> = db.userVocabularyDao().getTopVocabulary()

    val isOfflineMode: Flow<Boolean> = db.appSettingDao()
        .observeValue(KEY_OFFLINE_MODE)
        .map { storedValue -> storedValue.equals("true", ignoreCase = true) }

    suspend fun setOfflineMode(enabled: Boolean) {
        db.appSettingDao().upsert(AppSetting(KEY_OFFLINE_MODE, enabled.toString()))
    }

    suspend fun insertShortcut(shortcut: ShortcutTemplate) {
        db.shortcutDao().insertShortcut(shortcut)
    }

    suspend fun deleteShortcut(shortcut: ShortcutTemplate) {
        db.shortcutDao().deleteShortcut(shortcut)
    }

    suspend fun deleteShortcutById(id: Int) {
        db.shortcutDao().deleteById(id)
    }

    suspend fun insertLog(log: WritingLog) {
        val sanitizedText = PrivacyTextSanitizer
            .sanitizeText(log.originalText)
            .sanitized
            .take(MAX_LOG_TEXT_CHARS)
        db.writingLogDao().insertLog(log.copy(originalText = sanitizedText))
        pruneWritingLogs()
    }

    private suspend fun pruneWritingLogs() {
        val cutoff = System.currentTimeMillis() - LOG_RETENTION_MILLIS
        db.writingLogDao().deleteOlderThan(cutoff)
        db.writingLogDao().keepNewest(MAX_WRITING_LOGS)
    }

    suspend fun clearLogs() {
        db.writingLogDao().clearAll()
    }

    suspend fun insertCorrection(correction: LearnedCorrection) {
        db.learnedCorrectionDao().insertCorrection(correction)
    }

    suspend fun getCorrectionForTypo(typo: String): LearnedCorrection? {
        return db.learnedCorrectionDao().getCorrectionForTypo(typo)
    }

    suspend fun deleteCorrectionById(id: Int) {
        db.learnedCorrectionDao().deleteById(id)
    }

    suspend fun clearCorrections() {
        db.learnedCorrectionDao().clearAll()
    }

    suspend fun insertWord(word: UserVocabulary) {
        db.userVocabularyDao().insertWord(word)
    }

    /** Atomically bumps a word's usage count, inserting it on first use. */
    suspend fun recordWordUsage(word: String) {
        val updated = db.userVocabularyDao().incrementWordCount(word, System.currentTimeMillis())
        if (updated == 0) {
            db.userVocabularyDao().insertWord(UserVocabulary(word = word, count = 1))
        }
    }

    suspend fun getWord(word: String): UserVocabulary? {
        return db.userVocabularyDao().getWord(word)
    }

    suspend fun clearVocabulary() {
        db.userVocabularyDao().clearAll()
    }
}
