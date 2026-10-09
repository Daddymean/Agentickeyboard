package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #101: unverified (legacy) imports default to Merge and gate Replace. */
class KeyboardPassportImportPolicyTest {

    private fun preview(legacy: Boolean, compatible: Boolean = true) = KeyboardPassportPreview(
        version = if (legacy) 0 else 2,
        createdAt = null,
        categories = setOf(PassportCategory.VOCABULARY),
        counts = PassportRecordCounts(vocabulary = 1),
        encrypted = false,
        requiresPassphrase = false,
        compatible = compatible,
        legacy = legacy
    )

    @Test
    fun defaultModeIsTheNonDestructiveOne() {
        assertEquals(KeyboardPassportImportMode.MERGE, KeyboardPassportImportPolicy.DEFAULT_MODE)
    }

    @Test
    fun verifiedPassportsCanReplaceWithoutTheExtraAcknowledgement() {
        val verified = preview(legacy = false)
        assertFalse(KeyboardPassportImportPolicy.requiresUnverifiedReplaceAcknowledgement(verified, KeyboardPassportImportMode.REPLACE))
        assertTrue(KeyboardPassportImportPolicy.canConfirm(verified, KeyboardPassportImportMode.REPLACE, unverifiedReplaceAcknowledged = false))
        assertTrue(KeyboardPassportImportPolicy.canConfirm(verified, KeyboardPassportImportMode.MERGE, unverifiedReplaceAcknowledged = false))
    }

    @Test
    fun unverifiedFilesMergeFreelyButReplaceNeedsExplicitAcknowledgement() {
        val legacy = preview(legacy = true)
        assertFalse(KeyboardPassportImportPolicy.requiresUnverifiedReplaceAcknowledgement(legacy, KeyboardPassportImportMode.MERGE))
        assertTrue(KeyboardPassportImportPolicy.canConfirm(legacy, KeyboardPassportImportMode.MERGE, unverifiedReplaceAcknowledged = false))

        assertTrue(KeyboardPassportImportPolicy.requiresUnverifiedReplaceAcknowledgement(legacy, KeyboardPassportImportMode.REPLACE))
        assertFalse(KeyboardPassportImportPolicy.canConfirm(legacy, KeyboardPassportImportMode.REPLACE, unverifiedReplaceAcknowledged = false))
        assertTrue(KeyboardPassportImportPolicy.canConfirm(legacy, KeyboardPassportImportMode.REPLACE, unverifiedReplaceAcknowledged = true))
    }

    @Test
    fun incompatiblePassportsCanNeverBeConfirmed() {
        for (legacy in listOf(false, true)) {
            for (mode in KeyboardPassportImportMode.entries) {
                assertFalse(KeyboardPassportImportPolicy.canConfirm(preview(legacy, compatible = false), mode, unverifiedReplaceAcknowledged = true))
            }
        }
    }
}
