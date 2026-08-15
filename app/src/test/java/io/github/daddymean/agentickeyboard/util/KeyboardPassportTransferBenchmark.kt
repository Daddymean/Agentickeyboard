package io.github.daddymean.agentickeyboard.util

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
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
    fun benchmarkShortcutImport() = runBlocking {
        // Pre-populate some current shortcuts
        val currentShortcuts = (1..1000).map {
            ShortcutTemplate(shortcut = "s${it}", template = "Shortcut template ${it}")
        }
        currentShortcuts.forEach { repository.insertShortcut(it) }

        // Incoming shortcuts
        val incomingShortcuts = (1..1000).map {
            ShortcutTemplate(shortcut = "new${it}", template = "New Shortcut ${it}")
        }

        // Measure naive vs optimized implementation
        val time = measureTimeMillis {
            // simulating what KeyboardPassportTransfer does currently
            database.runInTransaction {
                runBlocking {
                    if (currentShortcuts.isNotEmpty()) {
                        repository.deleteShortcuts(currentShortcuts)
                    }
                    if (incomingShortcuts.isNotEmpty()) {
                        repository.insertShortcuts(incomingShortcuts)
                    }
                }
            }
        }

        println("Optimized import time for 1000 shortcuts: $time ms")
    }
}
