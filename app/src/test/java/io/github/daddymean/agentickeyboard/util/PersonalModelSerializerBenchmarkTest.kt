package io.github.daddymean.agentickeyboard.util

import org.junit.Test
import kotlin.system.measureTimeMillis

class PersonalModelSerializerBenchmarkTest {

    @Test
    fun benchmarkSanitizeText() {
        val testText = """
            Here is an email: john.doe@example.com
            Here is a phone number: 555-555-0199
            Here is a credit card: 1234-5678-9012-3456
            Here is an SSN: 123-45-6789
            Here is an IP address: 192.168.1.1
            Here is a URL: https://www.example.com/path
            Here is a numeric ID: 123456789
            Some normal text without any personal info.
        """.trimIndent()

        // Warmup
        for (i in 0..100) {
            PersonalModelSerializer.sanitizeText(testText)
        }

        // Benchmark
        val iterations = 10000
        val time = measureTimeMillis {
            for (i in 0..iterations) {
                PersonalModelSerializer.sanitizeText(testText)
            }
        }

        println("BENCHMARK: sanitizeText took $time ms for $iterations iterations")
    }
}
