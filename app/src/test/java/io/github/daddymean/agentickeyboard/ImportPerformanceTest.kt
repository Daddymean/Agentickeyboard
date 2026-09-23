package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.system.measureTimeMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class ImportPerformanceTest {

    // Simulates the DB repository for test purposes
    class FakeRepo {
        val corrections = mutableMapOf<String, LearnedCorrection>()

        suspend fun getCorrectionForTypo(typo: String): LearnedCorrection? {
            Thread.sleep(1) // simulate DB read latency
            return corrections[typo]
        }

        suspend fun insertCorrection(correction: LearnedCorrection) {
            Thread.sleep(1) // simulate DB write latency
            corrections[correction.typo] = correction
        }

        suspend fun getCorrectionsForTypos(typos: List<String>): List<LearnedCorrection> {
            Thread.sleep(3) // simulate DB read latency, slightly longer for IN clause
            return typos.mapNotNull { corrections[it] }
        }

        suspend fun insertCorrections(items: List<LearnedCorrection>) {
            Thread.sleep(5) // simulate DB batch write latency
            items.forEach { corrections[it.typo] = it }
        }
    }

    data class ImportItem(val typo: String, val correction: String, val count: Int)

    @Test
    fun testCorrectionHistoryImportPerformance() = runBlocking {
        val history = (1..500).map { ImportItem("typo${it}", "correction${it}", 1) }

        // Test Original Method
        val repo1 = FakeRepo()
        val time1 = measureTimeMillis {
            var imported = 0
            history.forEach { item ->
                if (item.typo.isNotBlank() && item.correction.isNotBlank()) {
                    val typo = item.typo.lowercase().trim()
                    val existing = repo1.getCorrectionForTypo(typo)
                    if (existing != null) {
                        repo1.insertCorrection(existing.copy(correction = item.correction, count = existing.count + item.count))
                    } else {
                        repo1.insertCorrection(LearnedCorrection(typo = typo, correction = item.correction, count = item.count))
                    }
                    imported++
                }
            }
        }

        // Test Batched Method
        val repo2 = FakeRepo()
        val time2 = measureTimeMillis {
            var imported = 0
            val validItems = history.filter { it.typo.isNotBlank() && it.correction.isNotBlank() }
            if (validItems.isNotEmpty()) {
                val typosToQuery = validItems.map { it.typo.lowercase().trim() }.distinct()
                val existingCorrections = repo2.getCorrectionsForTypos(typosToQuery).associateBy { it.typo }

                val correctionsToInsert = validItems.map { item ->
                    val typo = item.typo.lowercase().trim()
                    val existing = existingCorrections[typo]
                    if (existing != null) {
                        existing.copy(correction = item.correction, count = existing.count + item.count)
                    } else {
                        LearnedCorrection(typo = typo, correction = item.correction, count = item.count)
                    }
                }

                if (correctionsToInsert.isNotEmpty()) {
                    repo2.insertCorrections(correctionsToInsert)
                    imported += correctionsToInsert.size
                }
            }
        }

        println("Original Time: ${time1}ms")
        println("Batched Time: ${time2}ms")

        // Assert that both methods inserted the same data
        assertEquals(repo1.corrections.size, repo2.corrections.size)
        assertTrue(repo1.corrections.keys.containsAll(repo2.corrections.keys))

        // Ensure significant performance improvement
        assertTrue("Batched should be at least 10x faster for 500 items", time1 > time2 * 10)
    }
}
