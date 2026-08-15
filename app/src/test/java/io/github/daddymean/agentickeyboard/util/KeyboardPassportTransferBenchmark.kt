package io.github.daddymean.agentickeyboard.util

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.CustomCommand
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class KeyboardPassportTransferBenchmark {
    private lateinit var db: AppDatabase
    private lateinit var repository: KeyboardRepository

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = KeyboardRepository(db)
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun benchmarkCustomCommandDeletion() = runBlocking {
        // Setup: Insert 1000 custom commands for old benchmark
        var numCommands = 1000
        for (i in 1..numCommands) {
            repository.insertCustomCommand(CustomCommand(id = i, token = "cmd$i", instruction = "instruction$i"))
        }

        var idsToDelete = (1..numCommands).toList()

        // Benchmark old N+1 approach (we still have it in repository, we can just call it to compare)
        val timeOld = measureTimeMillis {
            idsToDelete.forEach { id ->
                repository.deleteCustomCommandById(id)
            }
        }

        // Re-setup for new benchmark
        for (i in 1..numCommands) {
            repository.insertCustomCommand(CustomCommand(id = i, token = "cmd$i", instruction = "instruction$i"))
        }

        // Benchmark new optimized approach
        val timeNew = measureTimeMillis {
            repository.deleteCustomCommandsByIds(idsToDelete)
        }

        println("BENCHMARK_RESULT: timeOld = ${timeOld}ms, timeNew = ${timeNew}ms")
    }
}
