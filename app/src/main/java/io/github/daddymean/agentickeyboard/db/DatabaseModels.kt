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
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryLimits
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryPruner
import io.github.daddymean.agentickeyboard.util.ClipboardRetentionRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest

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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(shortcuts: List<ShortcutTemplate>)

    @Delete
    suspend fun deleteShortcut(shortcut: ShortcutTemplate)

    @Query("DELETE FROM shortcut_templates WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM shortcut_templates WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)
}

@Dao
interface WritingLogDao {
    @Query("SELECT * FROM writing_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<WritingLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: WritingLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<WritingLog>)

    @Query("DELETE FROM writing_logs WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)

    @Query("DELETE FROM writing_logs")
    suspend fun clearAll()

    // --- Keyboard learned-data screen (KEYBOARD-047) ---
    @Query("SELECT COUNT(*) FROM writing_logs")
    suspend fun count(): Int

    @Query("SELECT * FROM writing_logs ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WritingLog>

    @Query("DELETE FROM writing_logs WHERE id = :id")
    suspend fun deleteById(id: Int): Int
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

    @Query("SELECT COUNT(*) FROM word_bigrams")
    suspend fun count(): Int

    @Query("SELECT * FROM word_bigrams ORDER BY count DESC, firstWord ASC, secondWord ASC LIMIT :limit")
    suspend fun top(limit: Int): List<WordBigram>

    @Query("DELETE FROM word_bigrams WHERE firstWord = :first AND secondWord = :second")
    suspend fun delete(first: String, second: String): Int

    /** Every pair a forgotten word is part of, so it is never predicted again. */
    @Query("DELETE FROM word_bigrams WHERE firstWord = :word OR secondWord = :word")
    suspend fun deleteWithWord(word: String): Int
}

@Dao
interface AppPersonaDao {
    @Query("SELECT * FROM app_personas WHERE packageName = :packageName LIMIT 1")
    suspend fun getForApp(packageName: String): AppPersona?

    @Query("SELECT * FROM app_personas ORDER BY appLabel COLLATE NOCASE ASC, packageName ASC")
    fun getAllFlow(): Flow<List<AppPersona>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(persona: AppPersona)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(personas: List<AppPersona>)

    @Query("DELETE FROM app_personas WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM app_personas WHERE packageName IN (:packageNames)")
    suspend fun deleteAll(packageNames: List<String>)

    @Query("SELECT COUNT(*) FROM app_personas")
    suspend fun count(): Int

    @Query("SELECT * FROM app_personas ORDER BY appLabel COLLATE NOCASE ASC, packageName ASC")
    suspend fun getAllOnce(): List<AppPersona>

    @Query("DELETE FROM app_personas")
    suspend fun clearAll(): Int
}

@Dao
interface CustomCommandDao {
    @Query("SELECT * FROM custom_commands ORDER BY token ASC")
    fun getAll(): Flow<List<CustomCommand>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(command: CustomCommand)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(commands: List<CustomCommand>)

    @Query("DELETE FROM custom_commands WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("DELETE FROM custom_commands WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)
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

    @Query("SELECT COUNT(*) FROM clipboard_history")
    suspend fun count(): Int
}

@Dao
interface LearnedCorrectionDao {
    @Query("SELECT * FROM learned_corrections ORDER BY count DESC")
    fun getAllCorrections(): Flow<List<LearnedCorrection>>

    @Query("SELECT * FROM learned_corrections")
    suspend fun getAllCorrectionsOnce(): List<LearnedCorrection>

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

    @Query("SELECT COUNT(*) FROM learned_corrections")
    suspend fun count(): Int
}

@Dao
interface UserVocabularyDao {
    @Query("SELECT * FROM user_vocabulary ORDER BY count DESC LIMIT 150")
    fun getTopVocabulary(): Flow<List<UserVocabulary>>

    @Query("SELECT word FROM user_vocabulary WHERE count >= :minimumCount")
    fun getWordsWithMinimumCount(minimumCount: Int): Flow<List<String>>

    @Query("SELECT * FROM user_vocabulary WHERE word = :word LIMIT 1")
    suspend fun getWord(word: String): UserVocabulary?

    @Query("SELECT * FROM user_vocabulary WHERE word IN (:words)")
    suspend fun getWords(words: List<String>): List<UserVocabulary>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWord(word: UserVocabulary)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWords(words: List<UserVocabulary>)

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

    @Query("SELECT COUNT(*) FROM user_vocabulary")
    suspend fun count(): Int

    @Query("SELECT * FROM user_vocabulary ORDER BY count DESC, word ASC LIMIT :limit")
    suspend fun top(limit: Int): List<UserVocabulary>

    @Query("DELETE FROM user_vocabulary WHERE word = :word")
    suspend fun deleteWord(word: String): Int
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
        private val instanceGeneration = MutableStateFlow(0)
        internal val instanceChanges: Flow<Int> get() = instanceGeneration

        /** v5 adds indices for the per-keystroke lookups; no data changes. */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_writing_logs_timestamp` ON `writing_logs` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_learned_corrections_typo` ON `learned_corrections` (`typo`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_user_vocabulary_count` ON `user_vocabulary` (`count`)")
            }
        }

        /** v6 stores a human-readable app label alongside each per-app persona. */
        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `app_personas` ADD COLUMN `appLabel` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v7 adds the user-owned Snippet Vault without copying or deleting legacy stores. */
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
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
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
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

        /**
         * KEYBOARD-047: deleted rows (learned words, writing log, clipboard clips) are
         * overwritten with zeros instead of left in free pages. `secure_delete` is per
         * connection; it is set inside a transaction so it lands on the primary (writer)
         * connection, the only one Android uses for writes. See [KeyboardLearnedData] for
         * the WAL checkpoint and the residue that remains.
         */
        val SECURE_DELETE = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                enableSecureDelete(db)
            }
        }

        /** Turns `secure_delete` on for the writer connection; true when SQLite reports it on. */
        fun enableSecureDelete(db: SupportSQLiteDatabase): Boolean {
            var on = false
            db.beginTransaction()
            try {
                db.query("PRAGMA secure_delete = ON").use { if (it.moveToFirst()) on = it.getInt(0) == 1 }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            return on
        }

        const val NAME = "agentic_keyboard_database"

        /** Where the last open of the keyboard database landed (encrypted, plaintext fallback, in memory). */
        @Volatile
        var lastStatus: KeyboardDbStatus? = null
            internal set

        internal fun encryptedFactory(
            app: Context,
            engine: KeyboardDbEngine = SqlCipherEngine(),
            wrapper: DbKeyWrapper = KeystoreDbKeyWrapper(),
            now: () -> Long = System::currentTimeMillis,
            health: KeyboardDbHealth = KeyboardDbHealth(KeyboardDbHealth.file(KeyboardDbKey.keyFile(app.noBackupFilesDir).parentFile!!), now),
        ) = KeyboardDbOpenHelperFactory(
            migrator = { KeyboardDbMigrator(KeyboardDbKey(KeyboardDbKey.keyFile(app.noBackupFilesDir), wrapper), engine, now, health) },
            engine = engine,
            onStatus = { lastStatus = it }
        )

        /**
         * Retry a mid-process open only while the last instance was the in-memory fallback,
         * so a recovered Keystore is picked up without a process restart. The previous
         * instance is never closed: in-flight readers keep that handle, and holders go
         * through [KeyboardRepository]'s provider to see the new one.
         */
        internal fun shouldRecycle(status: KeyboardDbStatus?): Boolean = status?.ephemeral == true

        fun getDatabase(context: Context): AppDatabase {
            val app = context.applicationContext
            synchronized(this) {
                val existing = INSTANCE
                if (existing != null && !shouldRecycle(lastStatus)) return existing
                val instance = buildDatabase(app)
                if (existing != null) {
                    // Force prepare so lastStatus is current before we decide.
                    runCatching { instance.openHelper.writableDatabase }
                    if (lastStatus?.ephemeral == true) {
                        runCatching { instance.close() }
                        return existing
                    }
                    // Key came back: install the recovered instance. Do not close [existing].
                    INSTANCE = instance
                    instanceGeneration.value = instanceGeneration.value + 1
                    return instance
                }
                INSTANCE = instance
                instanceGeneration.value = instanceGeneration.value + 1
                return instance
            }
        }

        private fun buildDatabase(app: Context): AppDatabase =
            Room.databaseBuilder(
                app,
                AppDatabase::class.java,
                NAME
            )
            // Learned vocabulary/corrections ARE the product: real migrations
            // from v4 on. Destructive fallback stays only for the pre-v4
            // schemas that shipped before migrations existed.
            .addMigrations(MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .fallbackToDestructiveMigrationFrom(true, 1, 2, 3)
            .addCallback(SECURE_DELETE)
            // KEYBOARD-048: SQLCipher, with a one-time in-place migration of the old
            // plaintext file. secure_delete and the post-delete WAL checkpoint work as before.
            .openHelperFactory(encryptedFactory(app, engineForTests ?: SqlCipherEngine(), wrapperForTests ?: KeystoreDbKeyWrapper()))
            .build()

        @Volatile
        internal var engineForTests: KeyboardDbEngine? = null
        @Volatile
        internal var wrapperForTests: DbKeyWrapper? = null

        internal fun clearInstanceForTests() {
            INSTANCE = null
            lastStatus = null
        }

        /**
         * One-tap recovery after several failed key opens. Re-checks the key first so a
         * stale "unavailable" status cannot move a healthy database aside. The previous
         * Room instance is not closed; holders pick up the new one through the provider.
         */
        fun resetBrokenDatabase(context: Context): KeyboardDbResetResult = synchronized(this) {
            val app = context.applicationContext
            val engine = engineForTests ?: SqlCipherEngine()
            val wrapper = wrapperForTests ?: KeystoreDbKeyWrapper()
            val keyFile = KeyboardDbKey.keyFile(app.noBackupFilesDir)
            val now = System::currentTimeMillis
            val health = KeyboardDbHealth(KeyboardDbHealth.file(keyFile.parentFile!!), now)
            val key = KeyboardDbKey(keyFile, wrapper)
            val main = app.getDatabasePath(NAME)
            val migrator = KeyboardDbMigrator(key, engine, now, health)
            when (val read = key.read()) {
                is KeyboardDbKey.Read.Ok -> {
                    if (!main.isFile || engine.canOpen(main, read.sqlKey) == OpenProbe.Ok) {
                        health.recordSuccess()
                        lastStatus = status("encrypted", false, 0, health)
                        INSTANCE = null
                        instanceGeneration.value = instanceGeneration.value + 1
                        return KeyboardDbResetResult.KeyWorksAgain
                    }
                }
                else -> Unit
            }
            val outcome = migrator.resetBroken(main)
            lastStatus = status(
                if (outcome is KeyboardDbMigrator.Outcome.Encrypted) "encrypted" else "memory:key",
                false, 0, health
            )
            INSTANCE = null
            instanceGeneration.value = instanceGeneration.value + 1
            if (outcome is KeyboardDbMigrator.Outcome.Encrypted) KeyboardDbResetResult.CreatedNew else KeyboardDbResetResult.Failed
        }
    }
}

/** Outcome of a user-confirmed reset offer on the learned-data screen. */
enum class KeyboardDbResetResult { CreatedNew, KeyWorksAgain, Failed }

class KeyboardRepository(private val database: () -> AppDatabase) {
    constructor(db: AppDatabase) : this({ db })

    private val db get() = database()

    private fun <T> switching(flow: (AppDatabase) -> Flow<T>): Flow<T> =
        AppDatabase.instanceChanges.flatMapLatest { flow(db) }

    val allShortcuts: Flow<List<ShortcutTemplate>> get() = switching { it.shortcutDao().getAllShortcuts() }
    val allLogs: Flow<List<WritingLog>> get() = switching { it.writingLogDao().getAllLogs() }
    val allCorrections: Flow<List<LearnedCorrection>> get() = switching { it.learnedCorrectionDao().getAllCorrections() }
    val topVocabulary: Flow<List<UserVocabulary>> get() = switching { it.userVocabularyDao().getTopVocabulary() }
    fun vocabularyWordsWithMinimumCount(minimumCount: Int): Flow<List<String>> =
        switching { it.userVocabularyDao().getWordsWithMinimumCount(minimumCount) }
    val allCustomCommands: Flow<List<CustomCommand>> get() = switching { it.customCommandDao().getAll() }
    val allSavedSnippets: Flow<List<SavedSnippet>> get() = switching { it.savedSnippetDao().getAll() }
    val allClipboardHistory: Flow<List<ClipboardHistoryItem>> get() = switching { it.clipboardHistoryDao().getAll() }

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

    val allAppPersonas: Flow<List<AppPersona>> get() = switching { it.appPersonaDao().getAllFlow() }

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

    suspend fun getAllCorrectionsOnce(): List<LearnedCorrection> =
        db.learnedCorrectionDao().getAllCorrectionsOnce()

    suspend fun getCorrectionsForTypos(typos: List<String>): List<LearnedCorrection> {
        return db.learnedCorrectionDao().getCorrectionsForTypos(typos)
    }

    suspend fun insertCorrections(corrections: List<LearnedCorrection>) {
        db.learnedCorrectionDao().insertCorrections(corrections)
    }

    /**
     * Learns typo → correction pairs: bumps the count of an existing rule or adds a new one.
     * The read and the write share one transaction, so a rule deleted meanwhile can't be
     * written back from a stale read (#231 review 2, note 2).
     */
    suspend fun learnCorrections(pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        db.withTransaction {
            val dao = db.learnedCorrectionDao()
            val existing = dao.getCorrectionsForTypos(pairs.map { it.first }).associateBy { it.typo }
            dao.insertCorrections(pairs.map { (typo, correction) ->
                existing[typo]?.copy(correction = correction, count = existing.getValue(typo).count + 1)
                    ?: LearnedCorrection(typo = typo, correction = correction)
            })
        }
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

    // --- Batch writes for imports ---
    //
    // Passport and personal-model imports used to issue one DAO call per record
    // (and the personal-model import ran outside any transaction, so one commit
    // per record). These helpers move a whole category in a few statements.
    // Lists that become SQL bind arguments are chunked: the SQLite shipped on
    // API 26-29 caps a statement at 999 host parameters.

    suspend fun insertWords(words: List<UserVocabulary>) {
        if (words.isNotEmpty()) db.userVocabularyDao().insertWords(words)
    }

    suspend fun insertShortcuts(shortcuts: List<ShortcutTemplate>) {
        if (shortcuts.isNotEmpty()) db.shortcutDao().insertAll(shortcuts)
    }

    suspend fun deleteShortcutsByIds(ids: List<Int>) {
        ids.chunked(MAX_BIND_ARGS).forEach { db.shortcutDao().deleteByIds(it) }
    }

    suspend fun insertCustomCommands(commands: List<CustomCommand>) {
        if (commands.isNotEmpty()) db.customCommandDao().insertAll(commands)
    }

    suspend fun deleteCustomCommandsByIds(ids: List<Int>) {
        ids.chunked(MAX_BIND_ARGS).forEach { db.customCommandDao().deleteByIds(it) }
    }

    suspend fun upsertAppPersonas(personas: List<AppPersona>) {
        if (personas.isNotEmpty()) db.appPersonaDao().upsertAll(personas)
    }

    suspend fun deleteAppPersonas(packageNames: List<String>) {
        packageNames.chunked(MAX_BIND_ARGS).forEach { db.appPersonaDao().deleteAll(it) }
    }

    suspend fun insertLogs(logs: List<WritingLog>) {
        if (logs.isNotEmpty()) db.writingLogDao().insertAll(logs)
    }

    /**
     * Merges a personal-model export into the local model in one transaction:
     * the import lands whole or not at all.
     *
     * The result matches the former record-at-a-time loop: vocabulary counts
     * add up and keep the newest lastUsed; a correction adds its count to the
     * stored row for its typo and takes the incoming correction text (the last
     * one wins when a typo repeats); logs are appended. [corrections] must
     * already carry normalized (lowercase, trimmed) typos.
     */
    suspend fun importPersonalModel(
        vocabulary: List<UserVocabulary>,
        corrections: List<LearnedCorrection>,
        logs: List<WritingLog>
    ) {
        db.withTransaction {
            mergeVocabulary(vocabulary)
            mergeCorrections(corrections)
            insertLogs(logs)
        }
    }

    private suspend fun mergeVocabulary(incoming: List<UserVocabulary>) {
        // Fold repeats first so a word listed twice adds both counts, as it did
        // when each row read back the one written before it.
        val folded = LinkedHashMap<String, UserVocabulary>()
        incoming.forEach { item ->
            folded.merge(item.word, item) { a, b ->
                a.copy(count = a.count + b.count, lastUsed = maxOf(a.lastUsed, b.lastUsed))
            }
        }
        val dao = db.userVocabularyDao()
        folded.values.chunked(MAX_BIND_ARGS).forEach { chunk ->
            val existing = dao.getWords(chunk.map { it.word }).associateBy { it.word }
            dao.insertWords(chunk.map { item ->
                val stored = existing[item.word]
                UserVocabulary(
                    word = item.word,
                    count = (stored?.count ?: 0) + item.count,
                    lastUsed = maxOf(stored?.lastUsed ?: 0L, item.lastUsed, 1L)
                )
            })
        }
    }

    private suspend fun mergeCorrections(incoming: List<LearnedCorrection>) {
        val folded = LinkedHashMap<String, LearnedCorrection>()
        incoming.forEach { item ->
            folded.merge(item.typo, item) { a, b -> b.copy(count = a.count + b.count) }
        }
        val dao = db.learnedCorrectionDao()
        folded.values.chunked(MAX_BIND_ARGS).forEach { chunk ->
            // getCorrectionForTypo (LIMIT 1) returned the oldest row if a typo
            // was ever stored twice; keep updating that one.
            val existing = dao.getCorrectionsForTypos(chunk.map { it.typo })
                .groupBy { it.typo }
                .mapValues { (_, rows) -> rows.minBy { it.id } }
            dao.insertCorrections(chunk.map { item ->
                existing[item.typo]
                    ?.let { it.copy(correction = item.correction, count = it.count + item.count) }
                    ?: item
            })
        }
    }

    private companion object {
        /** Headroom under SQLITE_MAX_VARIABLE_NUMBER (999 before SQLite 3.32 / API 30). */
        const val MAX_BIND_ARGS = 900
    }
}
