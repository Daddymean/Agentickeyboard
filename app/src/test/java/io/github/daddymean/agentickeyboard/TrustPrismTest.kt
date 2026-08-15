package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.util.TrustPrism
import io.github.daddymean.agentickeyboard.util.TrustPrismMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustPrismTest {

    @Test
    fun secureFieldHasHighestPriority() {
        val status = TrustPrism.resolve(
            isOfflineMode = true,
            isSensitiveField = true,
            cloudRedactionEnabled = false
        )

        assertEquals(TrustPrismMode.SECURE_FIELD, status.mode)
        assertTrue(status.isProtected)
    }

    @Test
    fun offlineModeWinsOverCloudPolicy() {
        val status = TrustPrism.resolve(
            isOfflineMode = true,
            isSensitiveField = false,
            cloudRedactionEnabled = false
        )

        assertEquals(TrustPrismMode.OFFLINE_LOCAL, status.mode)
        assertTrue(status.isProtected)
    }

    @Test
    fun cloudModeShowsRedactionProtection() {
        val status = TrustPrism.resolve(
            isOfflineMode = false,
            isSensitiveField = false,
            cloudRedactionEnabled = true
        )

        assertEquals(TrustPrismMode.CLOUD_REDACTED, status.mode)
        assertTrue(status.isProtected)
        assertTrue(status.label.contains("redacted", ignoreCase = true))
    }

    @Test
    fun disabledCloudRedactionProducesWarningState() {
        val status = TrustPrism.resolve(
            isOfflineMode = false,
            isSensitiveField = false,
            cloudRedactionEnabled = false
        )

        assertEquals(TrustPrismMode.CLOUD_UNPROTECTED, status.mode)
        assertFalse(status.isProtected)
        assertTrue(status.label.contains("unredacted", ignoreCase = true))
    }

    @Test
    fun resolvesExhaustiveCombinationsCorrectly() {
        // Defines all 8 permutations of inputs to test and the expected mode
        val testCases = listOf(
            // Triple(isSensitiveField, isOfflineMode, cloudRedactionEnabled) to ExpectedMode
            // Sensitive Field takes top priority
            Triple(true, true, true) to TrustPrismMode.SECURE_FIELD,
            Triple(true, true, false) to TrustPrismMode.SECURE_FIELD,
            Triple(true, false, true) to TrustPrismMode.SECURE_FIELD,
            Triple(true, false, false) to TrustPrismMode.SECURE_FIELD,

            // Offline Mode is 2nd priority
            Triple(false, true, true) to TrustPrismMode.OFFLINE_LOCAL,
            Triple(false, true, false) to TrustPrismMode.OFFLINE_LOCAL,

            // Cloud logic applies if neither Sensitive Field nor Offline Mode is active
            Triple(false, false, true) to TrustPrismMode.CLOUD_REDACTED,
            Triple(false, false, false) to TrustPrismMode.CLOUD_UNPROTECTED
        )

        for ((inputs, expectedMode) in testCases) {
            val status = TrustPrism.resolve(
                isSensitiveField = inputs.first,
                isOfflineMode = inputs.second,
                cloudRedactionEnabled = inputs.third
            )
            assertEquals(
                "Failed on combination isSensitiveField=${inputs.first}, isOfflineMode=${inputs.second}, cloudRedactionEnabled=${inputs.third}",
                expectedMode,
                status.mode
            )
            // Protection relies strictly on being in unprotected cloud state or not.
            val expectedProtected = expectedMode != TrustPrismMode.CLOUD_UNPROTECTED
            assertEquals(
                "Protected status mismatched on combination isSensitiveField=${inputs.first}, isOfflineMode=${inputs.second}, cloudRedactionEnabled=${inputs.third}",
                expectedProtected,
                status.isProtected
            )
        }
    }
}
