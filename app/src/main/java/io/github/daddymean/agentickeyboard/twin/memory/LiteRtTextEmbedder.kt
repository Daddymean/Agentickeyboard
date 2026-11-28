package io.github.daddymean.agentickeyboard.twin.memory

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import java.io.File

/**
 * On-device EmbeddingGemma 2 via LiteRT-LM's [EmbeddingEngine] (Apache-2.0,
 * `com.google.ai.edge.litertlm:litertlm-android:0.19.1`).
 *
 * The engine is created lazily on the first [embed] call so unit tests never
 * load the native library unless they run inference with a real model file.
 *
 * CPU only: vendor NPU runtimes are not added.
 */
class LiteRtTextEmbedder(
    private val modelFile: File,
    private val cacheDir: File?
) : TextEmbedder, AutoCloseable {

    private var engine: EmbeddingEngine? = null

    @Synchronized
    private fun engine(): EmbeddingEngine {
        val existing = engine
        if (existing != null && existing.isInitialized()) return existing
        if (existing != null) {
            closeQuietly(existing)
            engine = null
        }
        cacheDir?.mkdirs()
        val config = EmbeddingEngineConfig(
            modelPath = modelFile.absolutePath,
            backend = Backend.CPU(),
            cacheDir = cacheDir?.absolutePath
        )
        return EmbeddingEngine(config).also {
            it.initialize()
            engine = it
        }
    }

    override fun embed(prefixedText: String): FloatArray {
        try {
            val response = engine().computeEmbedding(
                listOf(InputData.Text(prefixedText)),
                EmbeddingOptions(normalize = true)
            )
            return response.embedding.clone()
        } catch (e: Exception) {
            throw IllegalStateException("LiteRT-LM EmbeddingEngine failed", e)
        }
    }

    @Synchronized
    override fun close() {
        closeQuietly(engine)
        engine = null
    }

    private companion object {
        fun closeQuietly(engine: AutoCloseable?) {
            if (engine == null) return
            try {
                engine.close()
            } catch (_: Exception) {
                // Best-effort; a later initialize() creates a new engine.
            }
        }
    }
}
