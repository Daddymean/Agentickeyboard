package io.github.daddymean.agentickeyboard.network

import io.github.daddymean.agentickeyboard.util.IncomingMoodSession
import io.github.daddymean.agentickeyboard.util.IncomingSentiment
import io.github.daddymean.agentickeyboard.util.OnDeviceAi
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import io.github.daddymean.agentickeyboard.util.OnDeviceTone
import io.github.daddymean.agentickeyboard.util.ToneMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KEYBOARD-023: what leaves the phone for "warmer / calmer". The incoming message
 * must never be in the payload, online or on device; only the user's draft, the
 * mood label and its intensity.
 */
class ToneMatchPayloadTest {
    private val incoming = "Dana here. The surgery on Friday went badly and I'm furious with " +
        "St Mary hospital!! Call me at 555-201-3344"
    private val incomingWords = listOf("dana", "surgery", "friday", "furious", "mary", "hospital", "555", "3344")
    private val draft = "I can't talk right now, email me at keith.b@example.com"

    private fun badge() = IncomingMoodSession().update(incoming, now = 0)!!

    @After
    fun tearDown() {
        GeminiManager.onDeviceAi = null
    }

    @Test
    fun cloudRequestBodyHasTheDraftAndMoodButNeverTheIncomingMessage() {
        val badge = badge()
        assertEquals(IncomingSentiment.Mood.TENSE, badge.mood)
        val request = ToneMatch.request(draft, badge)!!
        // The exact JSON body generateText would send.
        val body = RetrofitClient.moshi.adapter(GenerateContentRequest::class.java)
            .toJson(textOnlyRequest(ToneMatch.prompt(request)))
        val lower = body.lowercase()
        incomingWords.forEach { assertFalse("leaked '$it': $body", it in lower) }
        assertFalse(incoming.lowercase() in lower)
        assertTrue("can't talk right now" in body.replace("\\u0027", "'"))
        assertTrue("tense" in lower)
        assertTrue("intensity ${"%.1f".format(badge.intensity)}" in body)

        // The redaction interceptor then strips the draft's email before it is sent.
        val redacted = CloudRequestRedactor.redact(body, "application/json; charset=UTF-8".toMediaType())
        assertFalse(redacted.toString(), "keith.b@example.com" in redacted.toString())
    }

    @Test
    fun theRequestTypeHasNoPlaceForTheIncomingMessage() {
        val strings = ToneMatch.Request::class.java.declaredFields
            .filter { it.type == String::class.java || it.type == CharSequence::class.java }
            .map { it.name }
        assertEquals(listOf("draft"), strings)
    }

    @Test
    fun onDevicePromptAlsoNeverSeesTheIncomingMessage() = runTest {
        val prompts = mutableListOf<String>()
        val texts = mutableListOf<String>()
        GeminiManager.onDeviceAi = object : OnDeviceAi {
            override val status = MutableStateFlow(OnDeviceAiStatus.AVAILABLE)
            override val promptStatus = MutableStateFlow(OnDeviceAiStatus.AVAILABLE)
            override suspend fun proofread(text: String): String? = null
            override suspend fun rewrite(text: String, tone: OnDeviceTone): String? { texts += text; return null }
            override suspend fun summarize(text: String): String? = null
            override suspend fun generate(prompt: String): String? { prompts += prompt; return "calmer draft" }
        }
        val result = GeminiManager.offlineMatchTone(ToneMatch.request(draft, badge())!!)
        assertEquals("calmer draft", result)
        assertEquals(1, prompts.size)
        (prompts + texts).forEach { sent ->
            incomingWords.forEach { assertFalse("leaked '$it'", it in sent.lowercase()) }
        }
    }

    @Test
    fun offlineWithoutNanoUsesTemplates() = runTest {
        GeminiManager.onDeviceAi = null
        val warm = IncomingMoodSession().update("Thanks so much, love it!", 0)!!
        assertEquals("sounds good! 😊", GeminiManager.offlineMatchTone(ToneMatch.request("sounds good.", warm)!!))
    }

    @Test
    fun warmerFallsBackToNanoFriendlyPresetWhenThePromptModelIsMissing() = runTest {
        GeminiManager.onDeviceAi = object : OnDeviceAi {
            override val status = MutableStateFlow(OnDeviceAiStatus.AVAILABLE)
            override val promptStatus = MutableStateFlow(OnDeviceAiStatus.UNSUPPORTED)
            override suspend fun proofread(text: String): String? = null
            override suspend fun rewrite(text: String, tone: OnDeviceTone): String? =
                if (tone == OnDeviceTone.FRIENDLY) "friendly: $text" else null
            override suspend fun summarize(text: String): String? = null
            override suspend fun generate(prompt: String): String? = error("prompt model unavailable")
        }
        val warm = IncomingMoodSession().update("Congrats!! so proud of you", 0)!!
        assertEquals("friendly: ok see you then",
            GeminiManager.offlineMatchTone(ToneMatch.request("ok see you then", warm)!!))
    }
}
