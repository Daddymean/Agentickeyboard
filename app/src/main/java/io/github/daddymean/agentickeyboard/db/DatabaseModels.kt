package io.github.daddymean.agentickeyboard.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryLimits
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryPruner
import io.github.daddymean.agentickeyboard.util.ClipboardRetentionRecord
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "shortcut_templates")
data class ShortcutTemplate(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val shortcut: String,
    val template: String
)

@Entity(tableName = "writing_logs", indices = [Index("timestamp")])
data class WritingLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val originalText: String,
    val sentiment: String,
    val toneScore: Float,
    val wordCount: Int,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "learned_corrections", indices = [Index("typo")])
data class LearnedCorrection(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val typo: String,
    val correction: String,
    val count: Int = 1
)

@Entity(tableName = "user_vocabulary", indices = [Index("count")])
data class UserVocabulary(
    @PrimaryKey val word: String,
    val count: Int = 1,
    val lastUsed: Long = System.currentTimeMillis()
)

/** Word-pair frequencies powering on-device next-word prediction. */
@Entity(tableName = "word_bigrams", primaryKeys = ["firstWord", "secondWord"])
data class WordBigram(
    val firstWord: String,
    val secondWord: String,
    val count: Int = 1
)

/** Remembers which style persona the user prefers in each target app. */
@Entity(tableName = "app_personas")
data class AppPersona(
    @PrimaryKey val packageName: String,
    val persona: String,
    // Human-readable app name, resolved by the IME (which has visibility to the
    // app it serves) and stored so the companion app can show "Slack" rather
    // than "com.Slack" without needing package-query permissions.
    val appLabel: String = ""
)

/** User-defined slash command: typing its token opens a custom AI rewrite. */
@Entity(tableName = "custom_commands")
data class CustomCommand(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val token: String,      // normalized: leading slash, lowercase, no spaces
    val instruction: String // rewrite style instruction forwarded to the AI
)

/**
 * User-owned reusable text in the Snippet Vault. Aliases and tags are stored as
 * newline-delimited strings so the Room contract stays simple and portable;
 * SnippetListCodec owns normalization at the UI/repository boundary.
 */
@Entity(
    tableName = "saved_snippets",
    indices = [Index("title"), Index("usageCount"), Index("lastUsedAt")]
)
data class SavedSnippet(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val content: String,
    val aliases: String = "",
    val tags: String = "",
    val usageCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
    val lastUsedAt: Long = 0L
)

/**
 * A privacy-filtered clipboard item retained locally after explicit opt-in.
 * contentHash is an opaque duplicate key; raw text never appears in lookup keys.
 */
@Entity(
    tableName = "clipboard_history",
    indices = [
        Index(value = ["contentHash"], unique = true),
        Index("pinned"),
        Index("lastSeenAt")
    ]
)
data class ClipboardHistoryItem(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val content: String,
    val contentHash: String,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = createdAt,
    val lastUsedAt: Long = 0L
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

    @Query("DELETE FROM writing_logs")
    suspend fun clearAll()
}

@Dao
interface WordBigramDao {
    @Query("SELECT * FROM word_bigrams WHERE firstWord = :word ORDER BY count DESC LIMIT :limit")
    suspend fun nextWords(word: String, limit: Int): List<WordBigram>

    @Query("UPDATE word_bigrams SET count = count + 1 WHERE firstWord = :first AND secondWord = :second")
    suspend fun increment(first: String, second: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bigram: WordBigram)

    /** Atomically bumps a word-pair count, inserting it on first use. */
    @Transaction
    suspend fun upsert(first: String, second: String) {
        if (increment(first, second) == 0) {
            insert(WordBigram(firstWord = first, secondWord = second))
        }
    }

    @Query("DELETE FROM word_bigrams")
    suspend fun clearAll()
}

@Dao
interface AppPersonaDao {
    @Query("SELECT * FROM app_personas WHERE packageName = :packageName LIMIT 1")
    suspend fun getForApp(packageName: String): AppPersona?

    @Query("SELECT * FROM app_personas ORDER BY appLabel COLLATE NOCASE ASC, packageName ASC")
    fun getAllFlow(): Flow<List<AppPersona>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(persona: AppPersona)

    @Query("DELETE FROM app_personas WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface CustomCommandDao {
    @Query("SELECT * FROM custom_commands ORDER BY token ASC")
    fun getAll(): Flow<List<CustomCommand>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(command: CustomCommand)

    @Query("DELETE FROM custom_commands WHERE id = :id")
    suspend fun deleteById(id: Int)
}

@Dao
interface SavedSnippetDao {
    @Query("SELECT * FROM saved_snippets ORDER BY title COLLATE NOCASE ASC, id ASC")
    fun getAll(): Flow<List<SavedSnippet>>

    @Query("SELECT * FROM saved_snippets WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): SavedSnippet?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snippet: SavedSnippet): Long

    @Query("UPDATE saved_snippets SET usageCount = usageCount + 1, lastUsedAt = :usedAt WHERE id = :id")
    suspend fun recordUse(id: Int, usedAt: Long): Int

    @Query("DELETE FROM saved_snippets WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM saved_snippets")
    suspend fun clearAll()
}

@Dao
interface ClipboardHistoryDao {
    @Query("SELECT * FROM clipboard_history ORDER BY pinned DESC, lastSeenAt DESC, id DESC")
    fun getAll(): Flow<List<ClipboardHistoryItem>>

    @Query("SELECT * FROM clipboard_history ORDER BY pinned DESC, lastSeenAt DESC, id DESC")
    suspend fun getAllNow(): List<ClipboardHistoryItem>

    @Query("SELECT * FROM clipboard_history WHERE id = :id LIMIT 1")
    suspend fun getById(id: Int): ClipboardHistoryItem?

    @Query("SELECT COUNT(*) FROM clipboard_history WHERE pinned = 1")
    suspend fun countPinned(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: ClipboardHistoryItem): Long

    @Query("UPDATE clipboard_history SET content = :content, lastSeenAt = :seenAt WHERE contentHash = :contentHash")
    suspend fun touchDuplicate(contentHash: String, content: String, seenAt: Long): Int

    @Query("UPDATE clipboard_history SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Int, pinned: Boolean): Int

    @Query("UPDATE clipboard_history SET lastUsedAt = :usedAt WHERE id = :id")
    suspend fun recordUse(id: Int, usedAt: Long): Int

    @Query("DELETE FROM clipboard_history WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM clipboard_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    @Query("DELETE FROM clipboard_history")
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

    @Query("SELECT * FROM learned_corrections WHERE typo IN (:typos)")
    suspend fun getCorrectionsForTypos(typos: List<String>): List<LearnedCorrection>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCorrections(corrections: List<LearnedCorrection>)

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

    /** Atomically bumps a word's usage count, inserting it on first use. */
    @Transaction
    suspend fun upsert(word: String, now: Long) {
        if (incrementWordCount(word, now) == 0) {
            insertWord(UserVocabulary(word = word, count = 1, lastUsed = now))
        }
    }

    @Query("DELETE FROM user_vocabulary")
    suspend fun clearAll()
}

@Database(
    entities = [
        ShortcutTemplate::class,
        WritingLog::class,
        LearnedCorrection::class,
        UserVocabulary::class,
        WordBigram::class,
        AppPersona::class,
        CustomCommand::class,
        SavedSnippet::class,
        ClipboardHistoryItem::class
    ],
    version = 8,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun shortcutDao(): ShortcutDao
    abstract fun writingLogDao(): WritingLogDao
    abstract fun learnedCorrectionDao(): LearnedCorrectionDao
    abstract fun userVocabularyDao(): UserVocabularyDao
    abstract fun wordBigramDao(): WordBigramDao
    abstract fun appPersonaDao(): AppPersonaDao
    abstract fun customCommandDao(): CustomCommandDao
    abstract fun savedSnippetDao(): SavedSnippetDao
    abstract fun clipboardHistoryDao(): ClipboardHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v5 adds indices for the per-keystroke lookups; no data changes. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_writing_logs_timestamp` ON `writing_logs` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_learned_corrections_typo` ON `learned_corrections` (`typo`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_vocabulary_count` ON `user_vocabulary` (`count`)")
            }
        }

        /** v6 stores a human-readable app label alongside each per-app persona. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `app_personas` ADD COLUMN `appLabel` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v7 adds the user-owned Snippet Vault without copying or deleting legacy stores. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_snippets` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `aliases` TEXT NOT NULL,
                        `tags` TEXT NOT NULL,
                        `usageCount` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_snippets_title` ON `saved_snippets` (`title`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_snippets_usageCount` ON `saved_snippets` (`usageCount`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_saved_snippets_lastUsedAt` ON `saved_snippets` (`lastUsedAt`)")
            }
        }

        /** v8 adds opt-in, privacy-filtered clipboard history. Existing data is untouched. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `clipboard_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `content` TEXT NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `pinned` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `lastSeenAt` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_clipboard_history_contentHash` ON `clipboard_history` (`contentHash`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_clipboard_history_pinned` ON `clipboard_history` (`pinned`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_clipboard_history_lastSeenAt` ON `clipboard_history` (`lastSeenAt`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "agentic_keyboard_database"
                )
                // Learned vocabulary/corrections ARE the product: real migrations
                // from v4 on. Destructive fallback stays only for the pre-v4
                // schemas that shipped before migrations existed.
                .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                .fallbackToDestructiveMigrationFrom(true, 1, 2, 3)
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
    val allCustomCommands: Flow<List<CustomCommand>> = db.customCommandDao().getAll()
    val allSavedSnippets: Flow<List<SavedSnippet>> = db.savedSnippetDao().getAll()
    val allClipboardHistory: Flow<List<ClipboardHistoryItem>> = db.clipboardHistoryDao().getAll()

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

    suspend fun deleteLogsOlderThan(cutoff: Long) {
        db.writingLogDao().deleteOlderThan(cutoff)
    }

    suspend fun clearLogs() {
        db.writingLogDao().clearAll()
    }

    // --- Snippet Vault ---

    suspend fun getSavedSnippet(id: Int): SavedSnippet? = db.savedSnippetDao().getById(id)

    suspend fun upsertSavedSnippet(snippet: SavedSnippet): Long = db.savedSnippetDao().upsert(snippet)

    suspend fun recordSavedSnippetUse(id: Int, usedAt: Long = System.currentTimeMillis()): Boolean {
        return db.savedSnippetDao().recordUse(id, usedAt) > 0
    }

    suspend fun deleteSavedSnippetById(id: Int) {
        db.savedSnippetDao().deleteById(id)
    }

    suspend fun clearSavedSnippets() {
        db.savedSnippetDao().clearAll()
    }

    // --- Optional local clipboard history ---

    suspend fun captureClipboard(
        content: String,
        contentHash: String,
        seenAt: Long = System.currentTimeMillis(),
        retentionDays: Int = ClipboardHistoryLimits.DEFAULT_RETENTION_DAYS
    ) {
        val dao = db.clipboardHistoryDao()
        val inserted = dao.insert(
            ClipboardHistoryItem(
                content = content,
                contentHash = contentHash,
                createdAt = seenAt,
                lastSeenAt = seenAt
            )
        )
        if (inserted == -1L) {
            dao.touchDuplicate(contentHash, content, seenAt)
        }
        pruneClipboardHistory(seenAt, retentionDays)
    }

    suspend fun setClipboardPinned(id: Int, pinned: Boolean): Boolean {
        val dao = db.clipboardHistoryDao()
        val existing = dao.getById(id) ?: return false
        if (pinned && !existing.pinned && dao.countPinned() >= ClipboardHistoryLimits.MAX_PINNED_ITEMS) {
            return false
        }
        return dao.setPinned(id, pinned) > 0
    }

    suspend fun recordClipboardUse(id: Int, usedAt: Long = System.currentTimeMillis()): Boolean {
        return db.clipboardHistoryDao().recordUse(id, usedAt) > 0
    }

    suspend fun deleteClipboardItem(id: Int) {
        db.clipboardHistoryDao().deleteById(id)
    }

    suspend fun clearClipboardHistory() {
        db.clipboardHistoryDao().clearAll()
    }

    suspend fun pruneClipboardHistory(
        now: Long = System.currentTimeMillis(),
        retentionDays: Int = ClipboardHistoryLimits.DEFAULT_RETENTION_DAYS
    ) {
        val dao = db.clipboardHistoryDao()
        val records = dao.getAllNow().map { item ->
            ClipboardRetentionRecord(
                id = item.id,
                pinned = item.pinned,
                lastSeenAt = item.lastSeenAt
            )
        }
        val ids = ClipboardHistoryPruner.plan(records, now, retentionDays).idsToDelete
        if (ids.isNotEmpty()) dao.deleteByIds(ids.toList())
    }

    // --- Next-word prediction (bigrams) ---

    /** Atomically bumps a word-pair count, inserting it on first use. */
    suspend fun recordBigram(first: String, second: String) {
        db.wordBigramDao().upsert(first, second)
    }

    suspend fun nextWords(word: String, limit: Int = 3): List<String> {
        return db.wordBigramDao().nextWords(word, limit).map { it.secondWord }
    }

    suspend fun clearBigrams() {
        db.wordBigramDao().clearAll()
    }

    // --- User-defined slash commands ---

    suspend fun insertCustomCommand(command: CustomCommand) {
        db.customCommandDao().insert(command)
    }

    suspend fun deleteCustomCommandById(id: Int) {
        db.customCommandDao().deleteById(id)
    }

    // --- Per-app persona memory ---

    val allAppPersonas: Flow<List<AppPersona>> = db.appPersonaDao().getAllFlow()

    suspend fun getAppPersona(packageName: String): String? {
        return db.appPersonaDao().getForApp(packageName)?.persona
    }

    suspend fun setAppPersona(packageName: String, persona: String, appLabel: String = "") {
        db.appPersonaDao().upsert(
            AppPersona(packageName = packageName, persona = persona, appLabel = appLabel)
        )
    }

    suspend fun deleteAppPersona(packageName: String) {
        db.appPersonaDao().delete(packageName)
    }

    // New on-device personalization repository functions
    suspend fun insertCorrection(correction: LearnedCorrection) {
        db.learnedCorrectionDao().insertCorrection(correction)
    }

    suspend fun getCorrectionForTypo(typo: String): LearnedCorrection? {
        return db.learnedCorrectionDao().getCorrectionForTypo(typo)
    }

    suspend fun getCorrectionsForTypos(typos: List<String>): List<LearnedCorrection> {
        return db.learnedCorrectionDao().getCorrectionsForTypos(typos)
    }

    suspend fun insertCorrections(corrections: List<LearnedCorrection>) {
        db.learnedCorrectionDao().insertCorrections(corrections)
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
        db.userVocabularyDao().upsert(word, System.currentTimeMillis())
    }

    suspend fun getWord(word: String): UserVocabulary? {
        return db.userVocabularyDao().getWord(word)
    }

    suspend fun clearVocabulary() {
        db.userVocabularyDao().clearAll()
    }
}
