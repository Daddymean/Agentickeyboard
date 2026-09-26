package dev.context.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dev.context.core.model.Episode
import dev.context.core.model.Event

/**
 * The phone's source of truth. Opened only inside the :context-app process —
 * clients reach it through IContextService, never by opening the file — so no
 * multi-instance invalidation is configured.
 */
@Database(entities = [Event::class, Episode::class], version = 1, exportSchema = true)
abstract class ContextDatabase : RoomDatabase() {
  abstract fun events(): EventDao

  abstract fun episodes(): EpisodeDao

  companion object {
    const val NAME = "context.db"

    @Volatile private var instance: ContextDatabase? = null

    /** Process-wide singleton (no DI framework in this project). */
    fun get(context: Context): ContextDatabase =
      instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(context.applicationContext, ContextDatabase::class.java, NAME)
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
