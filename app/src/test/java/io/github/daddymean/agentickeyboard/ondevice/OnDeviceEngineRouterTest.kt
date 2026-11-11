package io.github.daddymean.agentickeyboard.ondevice

import io.github.daddymean.agentickeyboard.util.OnDeviceAi
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import io.github.daddymean.agentickeyboard.util.OnDeviceTone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeEngine(
    status: OnDeviceAiStatus = OnDeviceAiStatus.AVAILABLE,
    var result: String? = "from-engine",
    var throwOnUse: Throwable? = null
) : OnDeviceAi {
    override val status = MutableStateFlow(status)
    override val promptStatus = this.status
    var calls = 0
        private set

    private fun answer(): String? {
        calls++
        throwOnUse?.let { throw it }
        return result
    }

    override suspend fun proofread(text: String) = answer()
    override suspend fun rewrite(text: String, tone: OnDeviceTone) = answer()
    override suspend fun summarize(text: String) = answer()
    override suspend fun generate(prompt: String) = answer()
}

class OnDeviceEngineRouterTest {

    private fun ctx(
        privacy: Boolean = false,
        nano: OnDeviceAiStatus = OnDeviceAiStatus.AVAILABLE,
        localEnabled: Boolean = true,
        localReady: Boolean = true,
        localSupported: Boolean = true
    ) = OnDeviceEngineRouter.Context(privacy, nano, localEnabled, localReady, localSupported)

    @Test
    fun prefersNanoWhenAvailableAndAllowed() {
        assertEquals(
            OnDeviceEngineRouter.Engine.NANO,
            OnDeviceEngineRouter.decide(ctx())
        )
    }

    @Test
    fun usesLocalGemmaWhenNanoUnsupported() {
        assertEquals(
            OnDeviceEngineRouter.Engine.LOCAL_GEMMA,
            OnDeviceEngineRouter.decide(ctx(nano = OnDeviceAiStatus.UNSUPPORTED))
        )
    }

    @Test
    fun usesLocalGemmaWhenNanoIsBlockedOrBusy() {
        assertEquals(
            OnDeviceEngineRouter.Engine.LOCAL_GEMMA,
            OnDeviceEngineRouter.decide(ctx(), nanoBlockedOrBusy = true)
        )
    }

    @Test
    fun existingBehaviorWhenLocalOffOrMissing() {
        assertEquals(
            OnDeviceEngineRouter.Engine.EXISTING,
            OnDeviceEngineRouter.decide(ctx(nano = OnDeviceAiStatus.UNSUPPORTED, localEnabled = false))
        )
        assertEquals(
            OnDeviceEngineRouter.Engine.EXISTING,
            OnDeviceEngineRouter.decide(ctx(nano = OnDeviceAiStatus.UNSUPPORTED, localReady = false))
        )
    }

    @Test
    fun privacyBlockedNeverSelectsAModel() {
        assertEquals(
            OnDeviceEngineRouter.Engine.EXISTING,
            OnDeviceEngineRouter.decide(ctx(privacy = true))
        )
    }

    @Test
    fun routeUsesNanoResult() = runTest {
        val nano = FakeEngine(result = "nano")
        val local = FakeEngine(result = "gemma")
        val out = OnDeviceEngineRouter.route(ctx(), { nano.proofread("x") }, { local.proofread("x") }, { "heuristic" })
        assertEquals("nano", out)
        assertEquals(1, nano.calls)
        assertEquals(0, local.calls)
    }

    @Test
    fun routeFallsToGemmaWhenNanoBlocked() = runTest {
        val nano = FakeEngine(throwOnUse = NanoUseBlockedException(NanoErrorCodes.BACKGROUND_USE_BLOCKED))
        val local = FakeEngine(result = "gemma")
        val out = OnDeviceEngineRouter.route(
            ctx(),
            { nano.proofread("x") },
            { local.proofread("x") },
            { "heuristic" }
        )
        assertEquals("gemma", out)
        assertEquals(1, nano.calls)
        assertEquals(1, local.calls)
    }

    @Test
    fun routeFallsToGemmaWhenNanoBusy() = runTest {
        val nano = FakeEngine(throwOnUse = NanoUseBlockedException(NanoErrorCodes.BUSY))
        val local = FakeEngine(result = "gemma")
        val out = OnDeviceEngineRouter.route(ctx(), { nano.rewrite("x", OnDeviceTone.SHORTEN) }, { local.rewrite("x", OnDeviceTone.SHORTEN) }, { "heuristic" })
        assertEquals("gemma", out)
    }

    @Test
    fun routeFallsToExistingWhenBothFail() = runTest {
        val nano = FakeEngine(throwOnUse = NanoUseBlockedException(NanoErrorCodes.BUSY))
        val local = FakeEngine(throwOnUse = IllegalStateException("engine down"))
        val out = OnDeviceEngineRouter.route(ctx(), { nano.proofread("x") }, { local.proofread("x") }, { "heuristic" })
        assertEquals("heuristic", out)
    }

    @Test
    fun privacyBlockedSkipsBothEngines() = runTest {
        val nano = FakeEngine(result = "nano")
        val local = FakeEngine(result = "gemma")
        val out = OnDeviceEngineRouter.route(
            ctx(privacy = true),
            { nano.proofread("x") },
            { local.proofread("x") },
            { "heuristic" }
        )
        assertEquals("heuristic", out)
        assertEquals(0, nano.calls)
        assertEquals(0, local.calls)
    }

    @Test
    fun cascadingAiHonorsPrivacyAndLocalFallback() = runTest {
        val nano = FakeEngine(throwOnUse = NanoUseBlockedException(NanoErrorCodes.BACKGROUND_USE_BLOCKED))
        val local = FakeEngine(result = "gemma")
        val ai = CascadingOnDeviceAi(nano, local) {
            OnDeviceEngineRouter.Context(
                privacyBlocked = false,
                nanoStatus = OnDeviceAiStatus.AVAILABLE,
                localEnabled = true,
                localReady = true
            )
        }
        assertEquals("gemma", ai.proofread("fixd"))
        val blocked = CascadingOnDeviceAi(nano, local) {
            OnDeviceEngineRouter.Context(true, OnDeviceAiStatus.AVAILABLE, true, true)
        }
        assertNull(blocked.proofread("secret"))
    }

    @Test
    fun nanoErrorCodesRecognizeBlockedAndBusy() {
        assertTrue(NanoErrorCodes.isBlockedOrBusy(NanoErrorCodes.BUSY))
        assertTrue(NanoErrorCodes.isBlockedOrBusy(NanoErrorCodes.BACKGROUND_USE_BLOCKED))
        assertTrue(NanoErrorCodes.isBlockedOrBusy(NanoUseBlockedException(30)))
        assertEquals(false, NanoErrorCodes.isBlockedOrBusy(8))
    }

    @Test
    fun unsupportedOrLowRamDeviceUsesNano() {
        assertEquals(
            OnDeviceEngineRouter.Engine.NANO,
            OnDeviceEngineRouter.decide(ctx(localSupported = false))
        )
        assertEquals(
            OnDeviceEngineRouter.Engine.EXISTING,
            OnDeviceEngineRouter.decide(ctx(nano = OnDeviceAiStatus.UNSUPPORTED, localSupported = false))
        )
    }

    @Test
    fun rewritePrefersGemmaWhenEligibleThenFallsToMlKit() = runTest {
        val nano = FakeEngine(result = "mlkit")
        val local = FakeEngine(throwOnUse = GemmaInferenceException(GemmaFailureReason.INIT_FAILED))
        val failures = mutableListOf<Pair<OnDeviceEngineRouter.Engine, String>>()
        val out = OnDeviceEngineRouter.route(
            ctx(nano = OnDeviceAiStatus.AVAILABLE),
            { nano.rewrite("x", OnDeviceTone.SHORTEN) },
            { local.rewrite("x", OnDeviceTone.SHORTEN) },
            { "heuristic" },
            preferLocalIfReady = true,
            onFailure = { engine, reason -> failures += engine to reason }
        )
        assertEquals("mlkit", out)
        assertEquals(1, local.calls)
        assertEquals(1, nano.calls)
        assertEquals(
            listOf(OnDeviceEngineRouter.Engine.LOCAL_GEMMA to GemmaFailureReason.INIT_FAILED.name),
            failures
        )
    }

    @Test
    fun rewriteFallsToMlKitOnInferenceException() = runTest {
        val nano = FakeEngine(result = "mlkit")
        val local = FakeEngine(throwOnUse = GemmaInferenceException(GemmaFailureReason.INFERENCE_FAILED))
        val out = OnDeviceEngineRouter.route(
            ctx(),
            { nano.rewrite("x", OnDeviceTone.FRIENDLY) },
            { local.rewrite("x", OnDeviceTone.FRIENDLY) },
            { "heuristic" },
            preferLocalIfReady = true
        )
        assertEquals("mlkit", out)
    }

    @Test
    fun rewriteFallsToMlKitOnTimeout() = runTest {
        val nano = FakeEngine(result = "mlkit")
        val local = FakeEngine(throwOnUse = GemmaInferenceException(GemmaFailureReason.TIMEOUT))
        val out = OnDeviceEngineRouter.route(
            ctx(),
            { nano.rewrite("x", OnDeviceTone.PROFESSIONAL) },
            { local.rewrite("x", OnDeviceTone.PROFESSIONAL) },
            { "heuristic" },
            preferLocalIfReady = true
        )
        assertEquals("mlkit", out)
        assertEquals(1, nano.calls)
    }

    @Test
    fun timeoutCancellationFromGemmaStillFallsToMlKit() = runTest {
        val nano = FakeEngine(result = "mlkit")
        var localCalls = 0
        val out = OnDeviceEngineRouter.route(
            ctx(),
            { nano.rewrite("x", OnDeviceTone.SHORTEN) },
            {
                localCalls++
                withTimeout(1) {
                    delay(1_000)
                    "late"
                }
            },
            { "heuristic" },
            preferLocalIfReady = true
        )
        assertEquals("mlkit", out)
        assertEquals(1, localCalls)
    }

    @Test
    fun missingOrCorruptModelUsesMlKitWithoutCallingGemma() = runTest {
        val nano = FakeEngine(result = "mlkit")
        val local = FakeEngine(result = "gemma")
        val out = OnDeviceEngineRouter.route(
            ctx(localReady = false),
            { nano.rewrite("x", OnDeviceTone.SHORTEN) },
            { local.rewrite("x", OnDeviceTone.SHORTEN) },
            { "heuristic" },
            preferLocalIfReady = true
        )
        assertEquals("mlkit", out)
        assertEquals(1, nano.calls)
        assertEquals(0, local.calls)
    }

    @Test
    fun lowRamUsesMlKitWithoutCallingGemma() = runTest {
        val nano = FakeEngine(result = "mlkit")
        val local = FakeEngine(result = "gemma")
        val out = OnDeviceEngineRouter.route(
            ctx(localSupported = false),
            { nano.rewrite("x", OnDeviceTone.SHORTEN) },
            { local.rewrite("x", OnDeviceTone.SHORTEN) },
            { "heuristic" },
            preferLocalIfReady = true
        )
        assertEquals("mlkit", out)
        assertEquals(0, local.calls)
        assertEquals(1, nano.calls)
    }

    @Test
    fun bothEnginesFailSurfacesUnavailable() = runTest {
        val nano = FakeEngine(throwOnUse = IllegalStateException("nano down"))
        val local = FakeEngine(throwOnUse = GemmaInferenceException(GemmaFailureReason.INFERENCE_FAILED))
        try {
            OnDeviceEngineRouter.route(
                ctx(),
                { nano.rewrite("x", OnDeviceTone.SHORTEN) },
                { local.rewrite("x", OnDeviceTone.SHORTEN) },
                { "heuristic" },
                preferLocalIfReady = true,
                surfaceUnavailable = true
            )
            fail("expected unavailable")
        } catch (e: OnDeviceRewriteUnavailableException) {
            assertEquals(OnDeviceRewriteUnavailableException.USER_MESSAGE, e.message)
        }
        assertEquals(1, local.calls)
        assertEquals(1, nano.calls)
    }

    @Test
    fun noEngineEligibleUsesFallbackWithoutUnavailable() = runTest {
        val nano = FakeEngine(result = "mlkit")
        val local = FakeEngine(result = "gemma")
        val out = OnDeviceEngineRouter.route(
            ctx(nano = OnDeviceAiStatus.UNSUPPORTED, localReady = false),
            { nano.rewrite("x", OnDeviceTone.SHORTEN) },
            { local.rewrite("x", OnDeviceTone.SHORTEN) },
            { "heuristic" },
            preferLocalIfReady = true,
            surfaceUnavailable = true
        )
        assertEquals("heuristic", out)
        assertEquals(0, nano.calls)
        assertEquals(0, local.calls)
    }

    @Test
    fun cascadingRewriteFallsToMlKitThenSurfacesUnavailable() = runTest {
        val nano = FakeEngine(result = "mlkit")
        val failing = FakeEngine(throwOnUse = GemmaInferenceException(GemmaFailureReason.TIMEOUT))
        val reasons = mutableListOf<String>()
        val ai = CascadingOnDeviceAi(nano, failing, onFailure = { _, reason -> reasons += reason }) {
            ctx(nano = OnDeviceAiStatus.AVAILABLE)
        }
        assertEquals("mlkit", ai.rewrite("hello", OnDeviceTone.SHORTEN))
        assertEquals(listOf(GemmaFailureReason.TIMEOUT.name), reasons)

        val blockedNano = FakeEngine(throwOnUse = IllegalStateException("nano down"))
        val blocked = CascadingOnDeviceAi(blockedNano, failing, onFailure = { _, _ -> }) {
            ctx()
        }
        try {
            blocked.rewrite("hello", OnDeviceTone.SHORTEN)
            fail("expected unavailable")
        } catch (e: OnDeviceRewriteUnavailableException) {
            assertEquals(OnDeviceRewriteUnavailableException.USER_MESSAGE, e.message)
        }
    }

    @Test
    fun cascadingRewriteSkipsEnginesOnPrivacyBlock() = runTest {
        val nano = FakeEngine(result = "mlkit")
        val local = FakeEngine(result = "gemma")
        val ai = CascadingOnDeviceAi(nano, local) { ctx(privacy = true) }
        assertNull(ai.rewrite("secret", OnDeviceTone.SHORTEN))
        assertEquals(0, nano.calls)
        assertEquals(0, local.calls)
    }

    @Test
    fun blockingGemmaHangFallsBackToMlKitWithinTimeout() = runBlocking {
        val nano = FakeEngine(result = "mlkit")
        val scheduler = GemmaNativeScheduler()
        val hang = CountDownLatch(1)
        val started = System.nanoTime()
        try {
            val out = OnDeviceEngineRouter.route(
                ctx(),
                { nano.rewrite("x", OnDeviceTone.SHORTEN) },
                {
                    scheduler.run(timeoutMs = 200) {
                        hang.await()
                        "late-gemma"
                    }
                },
                { "heuristic" },
                preferLocalIfReady = true
            )
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertEquals("mlkit", out)
            assertEquals(1, nano.calls)
            assertTrue("ML Kit fallback should happen near the 200ms timeout, was ${elapsedMs}ms", elapsedMs < 1_500)
        } finally {
            hang.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (scheduler.isBusy && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            scheduler.shutdown()
        }
    }
}
