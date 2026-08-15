package io.github.daddymean.agentickeyboard.util

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.CustomCommand
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.system.measureTimeMillis

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardPassportTransferBenchmark {
    private lateinit var database: AppDatabase
    private lateinit var repository: KeyboardRepository

    @Before
    fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = KeyboardRepository(database)
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun benchmarkCustomCommandsBulkOperations() = runBlocking {
        // Prepare 1000 items
        val items = (1..1000).map {
            CustomCommand(
                token = "/test${it}",
                instruction = "Testing ${it}"
            )
        }

        // Populate DB
        items.forEach { repository.insertCustomCommand(it) }

        val dbItems = database.customCommandDao().getAll().first()
        val itemIds = dbItems.map { it.id }

        // Measure bulk delete and insert as implemented in new apply function
        val time = measureTimeMillis {
            database.runInTransaction {
                 if (itemIds.isNotEmpty()) {
                     runBlocking { repository.deleteCustomCommandsByIds(itemIds) }
                 }
                 if (items.isNotEmpty()) {
                     runBlocking { repository.insertCustomCommands(items) }
                 }
            }
        }
        println("Benchmark time for Bulk CustomCommand operations (1000 items): ${time} ms")
    }
}
