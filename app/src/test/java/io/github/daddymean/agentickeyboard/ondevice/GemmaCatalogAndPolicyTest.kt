package io.github.daddymean.agentickeyboard.ondevice

import io.github.daddymean.agentickeyboard.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GemmaCatalogAndPolicyTest {
    @Test
    fun gemmaPinIsCompleteAndHttps() {
        val spec = ModelCatalog.GEMMA_4_E2B
        assertEquals("gemma-4-e2b-it-litertlm", spec.id)
        assertEquals("gemma-4-E2B-it.litertlm", spec.fileName)
        assertEquals(2_588_147_712L, spec.sizeBytes)
        assertEquals(64, spec.sha256.length)
        assertTrue(spec.downloadUrl.startsWith("https://"))
        assertTrue(spec.downloadUrl.contains("3d48132a00cf76de57d926de30f5c5c5ed9b8c0d"))
        assertEquals("Apache-2.0", spec.license)
        val size = LocalGemmaController.formattedSize(spec)
        assertTrue(size.contains("2.59 GB"))
        assertTrue(size.replace(",", "").contains("2588147712"))
    }

    @Test
    fun policyRequiresOptInSizeAckAndWifi() {
        val base = ModelDownloadPolicy.Request(
            userOptedIn = true,
            sizeAcknowledged = true,
            unmeteredNetwork = true,
            allowMetered = false,
            alreadyReady = false,
            alreadyInProgress = false
        )
        assertEquals(ModelDownloadPolicy.Decision.Start, ModelDownloadPolicy.decide(base))
        assertEquals(
            ModelDownloadPolicy.Reason.OPT_IN_REQUIRED,
            (ModelDownloadPolicy.decide(base.copy(userOptedIn = false)) as ModelDownloadPolicy.Decision.Refuse).reason
        )
        assertEquals(
            ModelDownloadPolicy.Reason.SIZE_NOT_ACKNOWLEDGED,
            (ModelDownloadPolicy.decide(base.copy(sizeAcknowledged = false)) as ModelDownloadPolicy.Decision.Refuse).reason
        )
        assertEquals(
            ModelDownloadPolicy.Reason.WIFI_REQUIRED,
            (ModelDownloadPolicy.decide(base.copy(unmeteredNetwork = false)) as ModelDownloadPolicy.Decision.Refuse).reason
        )
        assertEquals(
            ModelDownloadPolicy.Decision.Start,
            ModelDownloadPolicy.decide(base.copy(unmeteredNetwork = false, allowMetered = true))
        )
        assertEquals(
            ModelDownloadPolicy.Decision.AlreadyReady,
            ModelDownloadPolicy.decide(base.copy(alreadyReady = true))
        )
    }
}
