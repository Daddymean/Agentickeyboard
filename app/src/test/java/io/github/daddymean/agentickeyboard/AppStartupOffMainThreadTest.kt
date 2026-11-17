package io.github.daddymean.agentickeyboard

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.models.ModelManager
import io.github.daddymean.agentickeyboard.network.GeminiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream

/**
 * LAB-002: Application.onCreate (run by Robolectric on the main thread before
 * each test) must not build the on-device AI stack itself. Building it creates
 * LocalGemmaController, whose readiness check SHA-256s the downloaded model.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppStartupOffMainThreadTest {

    @Test
    fun onDeviceStack_isBuiltOffTheMainThread() {
        val app = ApplicationProvider.getApplicationContext<AgenticKeyboardApplication>()
        assertTrue(Looper.getMainLooper().isCurrentThread)
        val deadline = System.currentTimeMillis() + 10_000
        while (app.onDeviceAiBuiltOnMainThread == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("built by the background warm-up, not onCreate", false, app.onDeviceAiBuiltOnMainThread)
        // GeminiManager resolves the same instance through the provider.
        assertSame(app.onDeviceAi, GeminiManager.onDeviceAi)
    }

    @Test
    fun settingAValue_replacesTheProvider() {
        val app = ApplicationProvider.getApplicationContext<AgenticKeyboardApplication>()
        assertNotNull(app)
        try {
            GeminiManager.onDeviceAi = null
            assertEquals(null, GeminiManager.onDeviceAi)
        } finally {
            GeminiManager.provideOnDeviceAi { app.onDeviceAi }
        }
    }

    /**
     * What one readiness check costs: SHA-256 throughput on this JVM, scaled to
     * the 2.59 GB Gemma 4 E2B file. Box JVM numbers, printed for a human; phone
     * flash and CPU differ. Before LAB-002 onCreate paid 1 such check when the
     * model was downloaded and 3 when Gemma was also enabled, on the main thread.
     */
    @Test
    fun benchmarkModelHashThroughput() {
        val size = 256L * 1024 * 1024
        fun stream() = object : InputStream() {
            var left = size
            override fun read(): Int = if (left-- > 0) 0x5a else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
        ModelManager.sha256(stream()) // warm-up
        val started = System.nanoTime()
        ModelManager.sha256(stream())
        val seconds = (System.nanoTime() - started) / 1e9
        val mbPerSecond = size / 1e6 / seconds
        val gemmaSeconds = 2.59e9 / 1e6 / mbPerSecond
        println(
            "LAB-002 sha256 on box JVM: ${"%.0f".format(mbPerSecond)} MB/s (CPU only, no disk); " +
                "one 2.59 GB check = ${"%.1f".format(gemmaSeconds)} s"
        )
    }
}
