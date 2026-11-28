package io.github.daddymean.agentickeyboard.ondevice

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File

/**
 * Gemma 4 E2B via LiteRT-LM's [Engine] (Apache-2.0,
 * `com.google.ai.edge.litertlm:litertlm-android:0.19.1`).
 *
 * The engine is created lazily on the first [generate] call so unit tests never
 * load the native library unless they run inference with a real model file.
 *
 * GPU first, then CPU. Vendor NPU runtimes are not added.
 */
class LiteRtGemmaEngine(
    private val modelFile: File,
    private val cacheDir: File?
) : AutoCloseable {

    private var engine: Engine? = null

    @Synchronized
    fun generate(prompt: String): String {
        val live = engine()
        val config = ConversationConfig(
            samplerConfig = SamplerConfig(
                topK = 10,
                topP = 0.9,
                temperature = 0.2,
                seed = 0
            )
        )
        val conversation = live.createConversation(config)
        return try {
            extractMessageText(conversation.sendMessage(prompt))
        } finally {
            closeQuietly(conversation)
        }
    }

    @Synchronized
    override fun close() {
        closeQuietly(engine)
        engine = null
    }

    private fun engine(): Engine {
        val existing = engine
        if (existing != null && existing.isInitialized()) return existing
        if (existing != null) {
            closeQuietly(existing)
            engine = null
        }
        cacheDir?.mkdirs()
        val cachePath = cacheDir?.absolutePath
        val created = tryCreate(Backend.GPU(), cachePath)
            ?: tryCreate(Backend.CPU(), cachePath)
            ?: throw IllegalStateException("LiteRT-LM Engine failed to initialize on GPU and CPU")
        engine = created
        return created
    }

    private fun tryCreate(backend: Backend, cachePath: String?): Engine? {
        var candidate: Engine? = null
        return try {
            val config = EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                cacheDir = cachePath
            )
            Engine(config).also {
                candidate = it
                it.initialize()
            }
        } catch (_: Exception) {
            closeQuietly(candidate)
            null
        }
    }

    internal companion object {
        fun extractMessageText(message: Message): String =
            extractContentText(message.contents.contents, message.toString())

        fun extractContentText(parts: List<Content>, fallback: String): String {
            val sb = StringBuilder()
            for (part in parts) {
                if (part is Content.Text && part.text.isNotEmpty()) {
                    sb.append(part.text)
                }
            }
            return if (sb.isEmpty()) fallback else sb.toString()
        }

        private fun closeQuietly(closeable: AutoCloseable?) {
            if (closeable == null) return
            try {
                closeable.close()
            } catch (_: Exception) {
                // Best-effort; a later initialize() creates a new engine.
            }
        }
    }
}
