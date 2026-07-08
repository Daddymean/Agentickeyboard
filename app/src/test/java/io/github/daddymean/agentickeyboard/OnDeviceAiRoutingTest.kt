package io.github.daddymean.agentickeyboard

import io.github.daddymean.agentickeyboard.network.GeminiManager
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
    initialPromptStatus: OnDeviceAiStatus = OnDeviceAiStatus.AVAILABLE,
    var proofreadResult: String? = null,
    var rewriteResult: String? = null,
    var summarizeResult: String? = null,
    var generateResult: String? = null,
    var throwOnUse: Boolean = false
) : OnDeviceAi {
    override val status = MutableStateFlow(initialStatus)
    override val promptStatus = MutableStateFlow(initialPromptStatus)
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
    override suspend fun generate(prompt: String) = answer(generateResult)
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

    // --- Phase 2: freeform prompt routing gated on promptStatus ---

    @Test
    fun promptPathUsesGenerateWhenPromptFeatureAvailable() = runTest {
        val ai = FakeOnDeviceAi(generateResult = "on-device reply")
        val result = OnDeviceAiRouter.route(
            ai,
            onDevice = { it.generate("p") },
            fallback = { "heuristic" },
            statusOf = { it.promptStatus.value }
        )
        assertEquals("on-device reply", result)
        assertEquals(1, ai.calls)
    }

    @Test
    fun promptPathFallsBackWhenPromptFeatureUnavailable() = runTest {
        // Task features available, prompt feature not: the prompt path must not run.
        val ai = FakeOnDeviceAi(
            initialStatus = OnDeviceAiStatus.AVAILABLE,
            initialPromptStatus = OnDeviceAiStatus.UNSUPPORTED,
            generateResult = "on-device reply"
        )
        val result = OnDeviceAiRouter.route(
            ai,
            onDevice = { it.generate("p") },
            fallback = { "heuristic" },
            statusOf = { it.promptStatus.value }
        )
        assertEquals("heuristic", result)
        assertEquals(0, ai.calls)
    }

    @Test
    fun taskAndPromptGatesAreIndependent() = runTest {
        // Prompt feature available, task features not: task path falls back,
        // prompt path runs. Proves the two statuses don't couple.
        val ai = FakeOnDeviceAi(
            initialStatus = OnDeviceAiStatus.UNSUPPORTED,
            initialPromptStatus = OnDeviceAiStatus.AVAILABLE,
            proofreadResult = "fixed",
            generateResult = "generated"
        )
        val taskResult = OnDeviceAiRouter.route(ai, { it.proofread("x") }, { "heuristic" })
        val promptResult = OnDeviceAiRouter.route(
            ai, { it.generate("x") }, { "heuristic" }, statusOf = { it.promptStatus.value }
        )
        assertEquals("heuristic", taskResult)
        assertEquals("generated", promptResult)
    }

    // --- Phase 2: prompt-output parsing helpers (GeminiManager, pure) ---

    @Test
    fun parseReplyLinesStripsNumberingBulletsAndQuotes() {
        val raw = "1. Sounds good!\n- \"On my way\"\n* See you soon"
        assertEquals(listOf("Sounds good!", "On my way", "See you soon"), GeminiManager.parseReplyLines(raw))
    }

    @Test
    fun parseReplyLinesDedupesAndCapsAtThree() {
        val raw = "Yes\nYes\nMaybe\nNo\nLater"
        assertEquals(listOf("Yes", "Maybe", "No"), GeminiManager.parseReplyLines(raw))
    }

    @Test
    fun parseReplyLinesReturnsNullForEmpty() {
        assertNull(GeminiManager.parseReplyLines(null))
        assertNull(GeminiManager.parseReplyLines("   \n  \n"))
    }

    @Test
    fun normalizeSentimentMapsKnownWordsAndRejectsOthers() {
        assertEquals("Professional", GeminiManager.normalizeSentiment("Professional."))
        assertEquals("Joyful", GeminiManager.normalizeSentiment("joyful"))
        assertEquals("Urgent", GeminiManager.normalizeSentiment("URGENT!!!"))
        assertEquals("Empathetic", GeminiManager.normalizeSentiment("Empathetic\n"))
        assertNull(GeminiManager.normalizeSentiment("whatever"))
        assertNull(GeminiManager.normalizeSentiment(null))
        assertNull(GeminiManager.normalizeSentiment(""))
    }
}
