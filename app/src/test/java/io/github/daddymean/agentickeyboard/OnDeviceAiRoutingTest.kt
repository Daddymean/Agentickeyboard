package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.ui.KeyboardViewModel
import io.github.daddymean.agentickeyboard.util.OnDeviceAi
import io.github.daddymean.agentickeyboard.util.OnDeviceAiRouter
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import io.github.daddymean.agentickeyboard.util.OnDeviceTone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM fake: canned results, settable availability, call counting. */
private class FakeOnDeviceAi(
    initialStatus: OnDeviceAiStatus = OnDeviceAiStatus.AVAILABLE,
    var proofreadResult: String? = null,
    var rewriteResult: String? = null,
    var summarizeResult: String? = null,
    var throwOnUse: Boolean = false
) : OnDeviceAi {
    override val status = MutableStateFlow(initialStatus)
    var calls = 0
        private set

    private fun <T> answer(value: T?): T? {
        calls++
        if (throwOnUse) throw IllegalStateException("AICore fell over")
        return value
    }

    override suspend fun proofread(text: String) = answer(proofreadResult)
    override suspend fun rewrite(text: String, tone: OnDeviceTone) = answer(rewriteResult)
    override suspend fun summarize(text: String) = answer(summarizeResult)
}

class OnDeviceAiRoutingTest {

    @Test
    fun usesOnDeviceResultWhenAvailable() = runTest {
        val ai = FakeOnDeviceAi(proofreadResult = "Fixed text.")
        val result = OnDeviceAiRouter.route(ai, { it.proofread("fixd text") }, { "heuristic" })
        assertEquals("Fixed text.", result)
        assertEquals(1, ai.calls)
    }

    @Test
    fun fallsBackWhenProviderMissing() = runTest {
        val result = OnDeviceAiRouter.route(null, { it.proofread("x") }, { "heuristic" })
        assertEquals("heuristic", result)
    }

    @Test
    fun fallsBackWhileNotAvailable() = runTest {
        for (status in listOf(
            OnDeviceAiStatus.CHECKING,
            OnDeviceAiStatus.DOWNLOADING,
            OnDeviceAiStatus.UNSUPPORTED
        )) {
            val ai = FakeOnDeviceAi(initialStatus = status, summarizeResult = "on-device")
            val result = OnDeviceAiRouter.route(ai, { it.summarize("long text") }, { "heuristic" })
            assertEquals("routing for $status", "heuristic", result)
            assertEquals("no on-device call for $status", 0, ai.calls)
        }
    }

    @Test
    fun fallsBackOnNullResult() = runTest {
        val ai = FakeOnDeviceAi(rewriteResult = null)
        val result = OnDeviceAiRouter.route(ai, { it.rewrite("x", OnDeviceTone.SHORTEN) }, { "heuristic" })
        assertEquals("heuristic", result)
        assertEquals(1, ai.calls)
    }

    @Test
    fun degradesSilentlyWhenOnDeviceThrows() = runTest {
        val ai = FakeOnDeviceAi(proofreadResult = "unused", throwOnUse = true)
        val result = OnDeviceAiRouter.route(ai, { it.proofread("x") }, { "heuristic" })
        assertEquals("heuristic", result)
    }

    @Test
    fun becomesActiveWhenDownloadCompletes() = runTest {
        val ai = FakeOnDeviceAi(initialStatus = OnDeviceAiStatus.DOWNLOADING, summarizeResult = "on-device")
        assertEquals("heuristic", OnDeviceAiRouter.route(ai, { it.summarize("t") }, { "heuristic" }))
        ai.status.value = OnDeviceAiStatus.AVAILABLE
        assertEquals("on-device", OnDeviceAiRouter.route(ai, { it.summarize("t") }, { "heuristic" }))
    }

    // --- Tone mapping: personas and iterate chips → Rewriting preset tones ---

    @Test
    fun personasMapToPresetTones() {
        assertEquals(OnDeviceTone.PROFESSIONAL, OnDeviceAi.toneFor("Professional"))
        assertEquals(OnDeviceTone.FRIENDLY, OnDeviceAi.toneFor("Joyful"))
        assertEquals(OnDeviceTone.FRIENDLY, OnDeviceAi.toneFor("Casual"))
        assertEquals(OnDeviceTone.FRIENDLY, OnDeviceAi.toneFor("Empathetic"))
    }

    @Test
    fun iterateChipInstructionsMapWhereTonesExist() {
        val byChip = KeyboardViewModel.RESULT_REFINEMENTS.mapValues { OnDeviceAi.toneFor(it.value) }
        assertEquals(OnDeviceTone.SHORTEN, byChip["Shorter"])
        assertEquals(OnDeviceTone.ELABORATE, byChip["Longer"])
        assertEquals(OnDeviceTone.FRIENDLY, byChip["Warmer"])
        assertEquals(OnDeviceTone.PROFESSIONAL, byChip["More formal"])
        // "Firmer" has no preset equivalent; it must stay on the heuristic path
        // rather than be mis-toned.
        assertNull(byChip["Firmer"])
    }

    @Test
    fun unknownTonesStayOnHeuristics() {
        assertNull(OnDeviceAi.toneFor(""))
        assertNull(OnDeviceAi.toneFor("like a pirate"))
    }
}
