package dev.context.core.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.context.core.model.Episode
import dev.context.core.model.Sensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EpisodeSyncTest {
  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun ep(id: String, title: String = id, sensitivity: Int = Sensitivity.PERSONAL) =
    Episode(id, 0, 100, "work", title, "", "[]", sensitivity)

  @Test
  fun upsertChangedStampsOnlyNewAndChangedEpisodes() {
    val db = ContextDatabase.inMemory(context)
    val dao = db.episodes()

    assertEquals(2, dao.upsertChanged(listOf(ep("a"), ep("b")), nowMs = 1_000))
    assertEquals(1_000, dao.byId("a")?.updatedMs)

    // Re-deriving identical content writes nothing, even with a stale updatedMs.
    assertEquals(0, dao.upsertChanged(listOf(ep("a"), ep("b")), nowMs = 2_000))
    assertEquals(1_000, dao.byId("a")?.updatedMs)

    assertEquals(1, dao.upsertChanged(listOf(ep("a"), ep("b", title = "renamed")), nowMs = 3_000))
    assertEquals(1_000, dao.byId("a")?.updatedMs)
    assertEquals(3_000, dao.byId("b")?.updatedMs)
    db.close()
  }

  @Test
  fun changedSincePagesThroughASharedTimestampWithoutSkipping() {
    val db = ContextDatabase.inMemory(context)
    val dao = db.episodes()
    // One batch: five rows stamped with the same updatedMs.
    dao.upsertChanged((1..5).map { ep("e$it") }, nowMs = 500)
    dao.upsertChanged(listOf(ep("late")), nowMs = 900)

    val seen = mutableListOf<String>()
    var since = 0L
    var after = ""
    while (true) {
      val page = dao.changedSince(since, after, limit = 2)
      if (page.isEmpty()) break
      seen += page.map { it.id }
      since = page.last().updatedMs
      after = page.last().id
    }
    assertEquals(listOf("e1", "e2", "e3", "e4", "e5", "late"), seen)
    db.close()
  }

  @Test
  fun changedSinceIncludesEpisodesThatBecameUnsyncable() {
    val db = ContextDatabase.inMemory(context)
    val dao = db.episodes()
    dao.upsertChanged(listOf(ep("a")), nowMs = 100)
    dao.upsertChanged(listOf(ep("a", sensitivity = Sensitivity.PRIVATE)), nowMs = 200)
    val changed = dao.changedSince(100, "a", limit = 10)
    assertEquals(listOf("a"), changed.map { it.id })
    assertTrue(!Sensitivity.isSyncable(changed.single().sensitivity))
    db.close()
  }

  @Test
  fun migratesV1DatabaseAndKeepsRows() {
    val name = "migration-test.db"
    context.deleteDatabase(name)
    // Hand-built v1 schema, exactly as Room 2.7 generated it for version 1.
    SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).apply { parentFile?.mkdirs() }, null).use { v1 ->
      v1.execSQL(
        "CREATE TABLE IF NOT EXISTS `events` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
          "`startMs` INTEGER NOT NULL, `endMs` INTEGER, `source` TEXT NOT NULL, `payload` TEXT NOT NULL, " +
          "`sensitivity` INTEGER NOT NULL, `createdMs` INTEGER NOT NULL, PRIMARY KEY(`id`))"
      )
      v1.execSQL("CREATE INDEX IF NOT EXISTS `index_events_startMs` ON `events` (`startMs`)")
      v1.execSQL("CREATE INDEX IF NOT EXISTS `index_events_type_startMs` ON `events` (`type`, `startMs`)")
      v1.execSQL(
        "CREATE TABLE IF NOT EXISTS `episodes` (`id` TEXT NOT NULL, `startMs` INTEGER NOT NULL, " +
          "`endMs` INTEGER NOT NULL, `kind` TEXT NOT NULL, `title` TEXT NOT NULL, `summary` TEXT NOT NULL, " +
          "`eventIds` TEXT NOT NULL, `sensitivity` INTEGER NOT NULL, PRIMARY KEY(`id`))"
      )
      v1.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_startMs` ON `episodes` (`startMs`)")
      v1.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_endMs` ON `episodes` (`endMs`)")
      v1.execSQL("CREATE INDEX IF NOT EXISTS `index_episodes_kind_startMs` ON `episodes` (`kind`, `startMs`)")
      v1.execSQL(
        "INSERT INTO `episodes` VALUES ('old', 0, 100, 'work', 'Old', '', '[]', 1)"
      )
      v1.version = 1
    }

    // Room validates the migrated schema against the v2 entities on open.
    val db = Room.databaseBuilder(context, ContextDatabase::class.java, name)
      .addMigrations(*ContextDatabase.MIGRATIONS)
      .allowMainThreadQueries()
      .build()
    val old = db.episodes().byId("old")
    assertEquals("Old", old?.title)
    assertEquals(0L, old?.updatedMs)
    // Pre-migration rows are picked up by a sync starting from the beginning.
    assertEquals(listOf("old"), db.episodes().changedSince(0, "", 10).map { it.id })
    db.close()
    context.deleteDatabase(name)
  }
}
