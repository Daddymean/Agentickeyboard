package io.github.daddymean.agentickeyboard.ondevice

import io.github.daddymean.agentickeyboard.util.OnDeviceAi
import io.github.daddymean.agentickeyboard.util.OnDeviceAiStatus
import io.github.daddymean.agentickeyboard.util.OnDeviceTone
import io.github.daddymean.agentickeyboard.util.SafeLog
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Kotlin [OnDeviceAi] wrapper around [LiteRtGemmaEngine]. This file must not
 * import `com.google.ai.edge.litertlm` — the AAR ships Kotlin 2.4 metadata.
 */
class GemmaLitertEngine(
    private val modelFile: () -> File?,
    private val cacheDir: File,
    private val enabled: () -> Boolean,
    private val timeoutMs: Long = GemmaGenerateGuard.DEFAULT_TIMEOUT_MS
) : OnDeviceAi {

    private val _status = MutableStateFlow(OnDeviceAiStatus.UNSUPPORTED)
    override val status = _status.asStateFlow()
    override val promptStatus = _status

    private val mutex = Mutex()
    private var engine: LiteRtGemmaEngine? = null

    fun refreshAvailability(ready: Boolean) {
        _status.value = if (enabled() && ready) OnDeviceAiStatus.AVAILABLE else OnDeviceAiStatus.UNSUPPORTED
    }

    override suspend fun proofread(text: String): String? =
        generate(promptForProofread(text))

    override suspend fun rewrite(text: String, tone: OnDeviceTone): String? =
        generate(promptForRewrite(text, tone))

    override suspend fun summarize(text: String): String? =
        generate(promptForSummarize(text))

    override suspend fun generate(prompt: String): String? {
        if (!enabled() || prompt.isBlank()) return null
        val file = modelFile()
        if (file == null || !file.isFile) {
            throw GemmaInferenceException(GemmaFailureReason.MODEL_MISSING)
        }
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    GemmaGenerateGuard.run(timeoutMs) {
                        try {
                            val live = engine ?: LiteRtGemmaEngine(file, cacheDir).also { engine = it }
                            cleanOutput(live.generate(prompt))
                        } catch (e: GemmaInferenceException) {
                            close()
                            throw e
                        } catch (t: Throwable) {
                            close()
                            throw t
                        }
                    }
                } catch (e: GemmaInferenceException) {
                    SafeLog.i(TAG, "LiteRT-LM Gemma generate failed: ${e.reason}", e)
                    throw e
                }
            }
        }
    }

    fun close() {
        val current = engine
        engine = null
        try {
            current?.close()
        } catch (_: Exception) {
        }
    }

    private fun cleanOutput(raw: String?): String? {
        var text = raw?.trim() ?: return null
        if (text.startsWith("```")) {
            text = text.removePrefix("```").trim().removeSuffix("```").trim()
        }
        return text.takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val TAG = "GemmaLitertEngine"

        fun promptForProofread(text: String): String =
            "Proofread the following English text. Fix grammar and spelling only. " +
                "Return only the corrected text, with no quotes or commentary.\n\n$text"

        fun promptForRewrite(text: String, tone: OnDeviceTone): String {
            val instruction = when (tone) {
                OnDeviceTone.SHORTEN -> "Rewrite the following English text so it is noticeably shorter and tighter, keeping the meaning."
                OnDeviceTone.ELABORATE -> "Rewrite the following English text so it is longer and more detailed, keeping the meaning."
                OnDeviceTone.FRIENDLY -> "Rewrite the following English text in a warmer, friendlier tone, keeping the meaning."
                OnDeviceTone.PROFESSIONAL -> "Rewrite the following English text in a more formal, professional tone, keeping the meaning."
            }
            return "$instruction Return only the rewritten text, with no quotes or commentary.\n\n$text"
        }

        fun promptForSummarize(text: String): String =
            "Summarize the following English text in one short sentence. " +
                "Return only the summary, with no quotes or commentary.\n\n$text"
    }
}
