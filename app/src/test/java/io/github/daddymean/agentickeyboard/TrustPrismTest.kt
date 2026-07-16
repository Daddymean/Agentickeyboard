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
}
