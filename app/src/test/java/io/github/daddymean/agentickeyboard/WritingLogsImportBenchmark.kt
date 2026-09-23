package io.github.daddymean.agentickeyboard

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.db.WritingLog
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
class WritingLogsImportBenchmark {

    private lateinit var db: AppDatabase
    private lateinit var repository: KeyboardRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(
            context, AppDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = KeyboardRepository(db)
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun benchmarkImportWritingLogs() = runBlocking {
        val logsToImport = (1..1000).map { i ->
            WritingLog(
                originalText = "text $i",
                sentiment = "0.5",
                toneScore = 0.5f,
                wordCount = 2,
                timestamp = System.currentTimeMillis() + i
            )
        }

        // Warmup
        for (i in 1..10) {
            repository.insertLog(WritingLog(originalText = "warmup $i", sentiment = "0.0", toneScore = 0f, wordCount = 1, timestamp = 0L))
        }

        val timeTaken = measureTimeMillis {
            logsToImport.forEach { item ->
                repository.insertLog(
                    WritingLog(
                        originalText = item.originalText,
                        sentiment = item.sentiment,
                        toneScore = item.toneScore,
                        wordCount = item.wordCount,
                        timestamp = item.timestamp
                    )
                )
            }
        }

        println("BENCHMARK_RESULT: Time taken for individual inserts: $timeTaken ms")

        val timeTakenBatch = measureTimeMillis {
            repository.insertAllLogs(
                logsToImport.map {
                    WritingLog(
                        originalText = it.originalText,
                        sentiment = it.sentiment,
                        toneScore = it.toneScore,
                        wordCount = it.wordCount,
                        timestamp = it.timestamp
                    )
                }
            )
        }
        println("BENCHMARK_RESULT: Time taken for batch insert: $timeTakenBatch ms")
    }
}
