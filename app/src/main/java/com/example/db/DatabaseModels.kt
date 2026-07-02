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
import kotlinx.coroutines.flow.Flow

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

    @Query("DELETE FROM user_vocabulary")
    suspend fun clearAll()
}

@Database(
    entities = [
        ShortcutTemplate::class,
        WritingLog::class,
        LearnedCorrection::class,
        UserVocabulary::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shortcutDao(): ShortcutDao
    abstract fun writingLogDao(): WritingLogDao
    abstract fun learnedCorrectionDao(): LearnedCorrectionDao
    abstract fun userVocabularyDao(): UserVocabularyDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agentic_keyboard_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class KeyboardRepository(private val db: AppDatabase) {
    val allShortcuts: Flow<List<ShortcutTemplate>> = db.shortcutDao().getAllShortcuts()
    val allLogs: Flow<List<WritingLog>> = db.writingLogDao().getAllLogs()
    val allCorrections: Flow<List<LearnedCorrection>> = db.learnedCorrectionDao().getAllCorrections()
    val topVocabulary: Flow<List<UserVocabulary>> = db.userVocabularyDao().getTopVocabulary()

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
        db.writingLogDao().insertLog(log)
    }

    suspend fun clearLogs() {
        db.writingLogDao().clearAll()
    }

    // New on-device personalization repository functions
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

    suspend fun getWord(word: String): UserVocabulary? {
        return db.userVocabularyDao().getWord(word)
    }

    suspend fun clearVocabulary() {
        db.userVocabularyDao().clearAll()
    }
}
