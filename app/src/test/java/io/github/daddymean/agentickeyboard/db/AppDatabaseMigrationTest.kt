package io.github.daddymean.agentickeyboard.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Validates the real migrations against the exported Room schemas in `app/schemas`.
 *
 * Each test builds a database exactly as an older app version would have (the
 * `createSql` and identity hash recorded in that version's schema JSON), then
 * opens it with the current [AppDatabase]. Room runs the migrations and, before
 * returning, checks every table, column and index against the current entities,
 * throwing if a migration drifted from them — the same validation
 * `MigrationTestHelper.runMigrationsAndValidate` performs. (MigrationTestHelper
 * itself reads schemas from APK assets, which AGP does not package for local
 * unit tests, hence this file-based equivalent.)
 *
 * Only versions with a committed schema can be tested: 4.json and 5.json were
 * never exported, so MIGRATION_4_5 and MIGRATION_5_6 are not covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun deleteDatabase() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun migrate6ToCurrentKeepsLearnedData() {
        createAtVersion(6) { db ->
            db.execSQL("INSERT INTO user_vocabulary (word, count, lastUsed) VALUES ('fantastic', 4, 123)")
            db.execSQL("INSERT INTO learned_corrections (typo, correction, count) VALUES ('teh', 'the', 3)")
            db.execSQL("INSERT INTO app_personas (packageName, persona, appLabel) VALUES ('com.example.chat', 'Joyful', 'Chat')")
        }

        openCurrent { db ->
            assertEquals("4", db.single("SELECT count FROM user_vocabulary WHERE word = 'fantastic'"))
            assertEquals("the", db.single("SELECT correction FROM learned_corrections WHERE typo = 'teh'"))
            assertEquals("Chat", db.single("SELECT appLabel FROM app_personas WHERE packageName = 'com.example.chat'"))
            assertEquals("0", db.single("SELECT COUNT(*) FROM saved_snippets"))
            assertEquals("0", db.single("SELECT COUNT(*) FROM clipboard_history"))
        }
    }

    @Test
    fun migrate7ToCurrentKeepsSnippets() {
        createAtVersion(7) { db ->
            db.execSQL(
                "INSERT INTO saved_snippets (title, content, aliases, tags, usageCount, createdAt, updatedAt, lastUsedAt) " +
                    "VALUES ('Address', '1 Main St', '', '', 2, 1, 1, 1)"
            )
        }

        openCurrent { db ->
            assertEquals("1 Main St", db.single("SELECT content FROM saved_snippets WHERE title = 'Address'"))
            assertEquals("0", db.single("SELECT COUNT(*) FROM clipboard_history"))
        }
    }

    @Test
    fun currentSchemaFileMatchesTheEntities() {
        // A fresh v8 database built from 8.json must pass Room's open-time validation
        // with no migration at all, i.e. the committed schema is the current one.
        createAtVersion(CURRENT_VERSION) { }
        openCurrent { db -> assertEquals("0", db.single("SELECT COUNT(*) FROM clipboard_history")) }
    }

    /** Creates [TEST_DB] the way the app at [version] would have, from its schema JSON. */
    private fun createAtVersion(version: Int, seed: (SupportSQLiteDatabase) -> Unit) {
        val database = schema(version).getJSONObject("database")
        assertEquals(version, database.getInt("version"))
        val callback = object : SupportSQLiteOpenHelper.Callback(version) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = database.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    db.execSQL(entity.getString("createSql").replace(TABLE_NAME, table))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) {
                        db.execSQL(indices.getJSONObject(j).getString("createSql").replace(TABLE_NAME, table))
                    }
                }
                val setup = database.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(TEST_DB).callback(callback).build()
        )
        try {
            seed(helper.writableDatabase)
        } finally {
            helper.close()
        }
    }

    /** Opens [TEST_DB] with the production migrations; Room validates the result. */
    private fun openCurrent(check: (SupportSQLiteDatabase) -> Unit) {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8
            )
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(CURRENT_VERSION, sqlite.version)
            check(sqlite)
        } finally {
            db.close()
        }
    }

    private fun SupportSQLiteDatabase.single(sql: String): String = query(sql).use {
        check(it.moveToFirst()) { "No row for: $sql" }
        it.getString(0)
    }

    private fun schema(version: Int): JSONObject =
        JSONObject(File(SCHEMA_DIR, "$version.json").readText())

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val CURRENT_VERSION = 8
        const val TABLE_NAME = "\${TABLE_NAME}"
        // Gradle runs unit tests with the module directory as the working directory.
        val SCHEMA_DIR = File("schemas/io.github.daddymean.agentickeyboard.db.AppDatabase")
    }
}
