package io.github.daddymean.agentickeyboard.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaEligibilityTest {

    private val ram = GemmaEligibility.MIN_TOTAL_RAM_BYTES
    private val abis = listOf("arm64-v8a")

    private fun decide(
        enabled: Boolean = true,
        present: Boolean = true,
        verified: Boolean = true,
        abis: List<String> = this.abis,
        ram: Long = this.ram
    ) = GemmaEligibility.decide(enabled, present, verified, abis, ram)

    @Test
    fun readyWhenEnabledVerifiedAndSupported() {
        assertNull(decide())
        assertTrue(GemmaEligibility.deviceSupported(abis, ram))
    }

    @Test
    fun notEnabled() {
        assertEquals(GemmaFailureReason.NOT_ENABLED, decide(enabled = false))
    }

    @Test
    fun unsupportedAbiSkipsGemma() {
        assertEquals(
            GemmaFailureReason.UNSUPPORTED_ABI,
            decide(abis = listOf("armeabi-v7a", "x86"))
        )
        assertFalse(GemmaEligibility.deviceSupported(listOf("armeabi-v7a"), ram))
    }

    @Test
    fun emulatorX86_64IsSupported() {
        assertNull(decide(abis = listOf("x86_64")))
        assertTrue(GemmaEligibility.abiSupported(listOf("x86", "x86_64")))
    }

    @Test
    fun lowMemorySkipsGemma() {
        assertEquals(
            GemmaFailureReason.LOW_MEMORY,
            decide(ram = ram - 1)
        )
        assertFalse(GemmaEligibility.deviceSupported(abis, ram - 1))
    }

    @Test
    fun modelMissing() {
        assertEquals(GemmaFailureReason.MODEL_MISSING, decide(present = false, verified = false))
    }

    @Test
    fun modelCorrupt() {
        assertEquals(GemmaFailureReason.MODEL_CORRUPT, decide(present = true, verified = false))
    }
}
