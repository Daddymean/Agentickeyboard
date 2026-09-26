package dev.context.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.context.core.model.Episode
import dev.context.core.model.Event

/**
 * The phone's source of truth. Opened only inside the :context-app process —
 * clients reach it through IContextService, never by opening the file — so no
 * multi-instance invalidation is configured.
 */
@Database(entities = [Event::class, Episode::class], version = 2, exportSchema = true)
abstract class ContextDatabase : RoomDatabase() {
  abstract fun events(): EventDao

  abstract fun episodes(): EpisodeDao

  companion object {
    const val NAME = "context.db"

    /** v1 → v2: `episodes.updatedMs`, the sync cursor. Existing rows read as 0 ("never synced"). */
    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `episodes` ADD COLUMN `updatedMs` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_updatedMs` ON `episodes` (`updatedMs`)")
      }
    }

    val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

    @Volatile private var instance: ContextDatabase? = null

    /** Process-wide singleton (no DI framework in this project). */
    fun get(context: Context): ContextDatabase =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, ContextDatabase::class.java, NAME)
          .addMigrations(*MIGRATIONS)
          .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
          .build()
          .also { instance = it }
      }

    /** Throwaway database for tests and previews. */
    fun inMemory(context: Context): ContextDatabase =
      Room.inMemoryDatabaseBuilder(context.applicationContext, ContextDatabase::class.java)
        .allowMainThreadQueries()
        .build()
  }
}
