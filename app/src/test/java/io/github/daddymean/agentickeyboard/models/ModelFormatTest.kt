package io.github.daddymean.agentickeyboard.models

import io.github.daddymean.agentickeyboard.ondevice.LocalGemmaController
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelFormatTest {
    @Test
    fun progressSizesAreShownInMegabytesAndGigabytes() {
        assertEquals("0 MB", LocalGemmaController.formattedBytes(0))
        assertEquals("958 MB", LocalGemmaController.formattedBytes(958_000_000))
        assertEquals("2.59 GB", LocalGemmaController.formattedBytes(2_588_147_712L))
    }
}
