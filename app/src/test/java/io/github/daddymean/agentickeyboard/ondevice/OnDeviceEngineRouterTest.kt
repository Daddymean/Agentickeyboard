package io.github.daddymean.agentickeyboard.ondevice

import io.github.daddymean.agentickeyboard.util.OnDeviceAi
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import io.github.daddymean.agentickeyboard.util.OnDeviceTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        localReady: Boolean = true
    ) = OnDeviceEngineRouter.Context(privacy, nano, localEnabled, localReady)

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
}
