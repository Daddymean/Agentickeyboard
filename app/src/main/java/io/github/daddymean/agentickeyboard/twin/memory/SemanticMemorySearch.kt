package io.github.daddymean.agentickeyboard.twin.memory

import android.content.Context
import io.github.daddymean.agentickeyboard.models.ModelCatalog
import io.github.daddymean.agentickeyboard.models.ModelManager
import io.github.daddymean.agentickeyboard.twin.KeystoreTwinCipher
import io.github.daddymean.agentickeyboard.twin.TwinCipher
import io.github.daddymean.agentickeyboard.twin.TwinPrivacyFilter
import io.github.daddymean.agentickeyboard.twin.TwinTextFilter
import java.io.File

/**
 * Semantic search over approved twin memories, with a keyword [MemoryIndex]
 * fallback when the EmbeddingGemma 2 model is absent or not opted in.
 *
 * Privacy filters run **before** any text is passed to [embedder]: password,
 * incognito, sensitive-hint, one-time-code and "Never learn in these apps"
 * fields are refused (the existing [TwinPrivacyFilter]), and text that
 * [TwinTextFilter.prepare] rejects is not embedded. Queries from a blocked
 * field return no hits and are not sent to keyword search either.
 */
class SemanticMemorySearch(
    private val vectors: EncryptedVectorIndex,
    private val keyword: MemoryIndex,
    private val memories: List<Memory>,
    private val embedder: TextEmbedder?,
    private val modelId: String,
    private val secureApps: Set<String> = emptySet(),
    private val ownPackage: String? = null
) : AutoCloseable {

    val isSemanticReady: Boolean get() = embedder != null

    /**
     * Ranks approved memories for [query]. When [field] is blocked by
     * [TwinPrivacyFilter], this returns an empty list and does not embed.
     */
    fun search(query: String, limit: Int = 5, field: TwinPrivacyFilter.Field? = null): List<SearchHit> {
        if (field != null && TwinPrivacyFilter.blocksCapture(field, secureApps, ownPackage)) return emptyList()
        val engine = embedder
        if (engine == null) return keyword.search(query, limit)
        val vector = engine.embedQuery(query)
        return vectors.search(vector, memories, limit)
    }

    /**
     * Embeds and stores [memory] unless [field] is blocked or [TwinTextFilter]
     * refuses the text. Returns true when a vector was written.
     */
    fun add(memory: Memory, field: TwinPrivacyFilter.Field? = null): Boolean {
        val engine = embedder ?: return false
        if (field != null && TwinPrivacyFilter.blocksCapture(field, secureApps, ownPackage)) return false
        if (TwinTextFilter.prepare(memory.text) == null) return false
        val vector = engine.embedDocument(memory.text)
        vectors.update { it.copy(modelId = modelId).upsert(memory.id, vector) }
        return true
    }

    fun remove(memoryId: String) {
        vectors.update { it.without(memoryId) }
    }

    /** Rebuilds missing vectors for approved memories and drops ids that left the vault. */
    fun sync(state: VaultState) {
        val engine = embedder ?: return
        val approved = state.memories.associateBy { it.id }
        vectors.update { current ->
            var next = current.copy(modelId = modelId).let { p ->
                p.copy(entries = p.entries.filter { it.memoryId in approved })
            }
            val have = next.entries.map { it.memoryId }.toSet()
            for (memory in state.memories) {
                if (memory.id in have) continue
                if (TwinTextFilter.prepare(memory.text) == null) continue
                val vector = engine.embedDocument(memory.text)
                next = next.upsert(memory.id, vector)
            }
            next
        }
    }

    override fun close() {
        keyword.close()
        (embedder as? AutoCloseable)?.close()
    }

    companion object {
        /**
         * Builds keyword search always, and the LiteRT-LM embedder only when the
         * user has opted in and the pinned model file is present and verified.
         */
        fun open(
            context: Context,
            state: VaultState,
            secureApps: Set<String>,
            cipher: TwinCipher = KeystoreTwinCipher(),
            allowMainThread: Boolean = false,
            models: ModelManager = ModelManager.forApp(context),
            embedderOverride: TextEmbedder? = null,
            cacheDir: File = File(context.noBackupFilesDir, "models/cache")
        ): SemanticMemorySearch {
            val keyword = MemoryIndex.build(context, state, allowMainThread = allowMainThread)
            val spec = ModelCatalog.EMBEDDING_GEMMA_2
            val file = if (models.isOptedIn(spec.id)) models.verifiedFile(spec) else null
            val embedder = embedderOverride ?: file?.let { LiteRtTextEmbedder(it, cacheDir) }
            return SemanticMemorySearch(
                vectors = EncryptedVectorIndex(EncryptedVectorIndex.file(context), cipher),
                keyword = keyword,
                memories = state.memories,
                embedder = embedder,
                modelId = spec.id,
                secureApps = secureApps
            )
        }
    }
}

