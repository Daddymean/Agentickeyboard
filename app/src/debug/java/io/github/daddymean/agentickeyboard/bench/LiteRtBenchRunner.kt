package io.github.daddymean.agentickeyboard.bench

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File

/**
 * Debug-only timing harness for LiteRT-LM rewrite models (model bake-off).
 *
 * Calls `com.google.ai.edge.litertlm:litertlm-android:0.19.1` directly. Each
 * prompt gets a fresh [Engine]: the LFM3 model card says one Engine per
 * conversation on LiteRT-LM up to 0.19.1 (LiteRT-LM issue #3165), and the same
 * rule is applied to Gemma so both are timed the same way. Sampler settings are
 * the app's (top-k 10, top-p 0.9, temperature 0.2, seed 0).
 *
 * Timing comes from LiteRT-LM's own `BenchmarkInfo` ([ExperimentalFlags.enableBenchmark]),
 * which is switched on only while this runner works and restored afterwards.
 * Not compiled into release builds (`src/debug`).
 */
@OptIn(ExperimentalApi::class)
class LiteRtBenchRunner(
    private val modelFile: File,
    private val cacheDir: File?,
    private val gpu: Boolean,
    private val disableThinking: Boolean
) {
    fun interface Progress {
        fun onResult(index: Int, total: Int, result: PromptResult)
    }

    fun run(
        ids: List<String>,
        prompts: List<String>,
        maxOutputTokens: Int,
        progress: Progress?
    ): List<PromptResult> {
        val previous = ExperimentalFlags.enableBenchmark
        ExperimentalFlags.enableBenchmark = true
        val out = ArrayList<PromptResult>(prompts.size)
        try {
            for (i in prompts.indices) {
                val result = runOne(ids[i], prompts[i], maxOutputTokens)
                out.add(result)
                progress?.onResult(i, prompts.size, result)
            }
        } finally {
            ExperimentalFlags.enableBenchmark = previous
        }
        return out
    }

    private fun runOne(id: String, prompt: String, maxOutputTokens: Int): PromptResult {
        var engine: Engine? = null
        var conversation: Conversation? = null
        val t0 = System.nanoTime()
        var initMs = 0.0
        return try {
            cacheDir?.mkdirs()
            engine = createEngine()
            initMs = (System.nanoTime() - t0) / 1e6
            val convConfig = ConversationConfig(
                samplerConfig = SamplerConfig(topK = 10, topP = 0.9, temperature = 0.2, seed = 0)
            )
            conversation = engine.createConversation(convConfig)
            val thinking = if (disableThinking) ThinkingConfig(enableThinking = false) else null
            val s = System.nanoTime()
            val message = conversation.sendMessage(
                text = prompt,
                maxOutputToken = maxOutputTokens,
                thinkingConfig = thinking
            )
            val wallMs = (System.nanoTime() - s) / 1e6
            val info = conversation.getBenchmarkInfo()
            PromptResult(
                id = id,
                ttftMs = info.timeToFirstTokenInSecond * 1000.0,
                prefillTokensPerSec = info.lastPrefillTokensPerSecond,
                decodeTokensPerSec = info.lastDecodeTokensPerSecond,
                decodeTokens = info.lastDecodeTokenCount,
                wallMs = wallMs,
                initMs = initMs,
                output = extractText(message),
                error = null
            )
        } catch (t: Throwable) {
            val cause = t.cause ?: t
            PromptResult(
                id = id,
                ttftMs = null,
                prefillTokensPerSec = null,
                decodeTokensPerSec = null,
                decodeTokens = null,
                wallMs = (System.nanoTime() - t0) / 1e6,
                initMs = initMs,
                output = null,
                error = "${cause.javaClass.simpleName}: ${cause.message}"
            )
        } finally {
            closeQuietly(conversation)
            closeQuietly(engine)
        }
    }

    private fun createEngine(): Engine {
        val backend: Backend = if (gpu) Backend.GPU() else Backend.CPU()
        val config = EngineConfig(
            modelPath = modelFile.absolutePath,
            backend = backend,
            cacheDir = cacheDir?.absolutePath
        )
        return Engine(config).apply { initialize() }
    }

    private companion object {
        fun extractText(message: Message): String =
            message.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString("") { it.text }

        fun closeQuietly(closeable: AutoCloseable?) {
            if (closeable == null) return
            try {
                closeable.close()
            } catch (_: Exception) {
                // Best-effort.
            }
        }
    }
}
