package io.github.daddymean.agentickeyboard.bench

import io.github.daddymean.agentickeyboard.models.ModelCatalog

/**
 * Model files the debug benchmark harness can time. None of these is
 * downloaded or bundled by the app: Keith pushes the file with adb into the
 * app's own external files directory (`files/bench/`), or, for Gemma 4 E2B,
 * the harness also accepts the copy the normal opt-in download already
 * verified. Sizes and SHA-256 are the pinned Hugging Face revisions.
 */
object BakeoffModels {
    enum class Kind { REWRITE, EMBEDDING }

    data class BenchModel(
        val key: String,
        val label: String,
        val kind: Kind,
        val fileName: String,
        val sizeBytes: Long,
        val sha256: String,
        val license: String,
        /** LFM3 thinks by default; the rewrite path must turn thinking off. */
        val disableThinking: Boolean = false,
    )

    val GEMMA_4_E2B = BenchModel(
        key = "gemma4-e2b",
        label = "Gemma 4 E2B (current)",
        kind = Kind.REWRITE,
        fileName = ModelCatalog.GEMMA_4_E2B.fileName,
        sizeBytes = ModelCatalog.GEMMA_4_E2B.sizeBytes,
        sha256 = ModelCatalog.GEMMA_4_E2B.sha256,
        license = "Apache-2.0",
    )

    /** litert-community/LFM3-1.7B-LiteRT@5cabb9d807905cb871557095729d70aabdec0e17 */
    val LFM3_INT4 = BenchModel(
        key = "lfm3-int4",
        label = "LFM3-1.7B mixed int4, 2k KV",
        kind = Kind.REWRITE,
        fileName = "LFM3-1.7B_mixed_int4_ekv2048.litertlm",
        sizeBytes = 1_441_620_928L,
        sha256 = "0400d8ffdb00e4bbe1209120e2c7baf05322a8c900839b0a94cc28f599011196",
        license = "LFM Open License v1.0 (not OSI-approved)",
        disableThinking = true,
    )

    /** Same revision; int8 weights stored as external weights (needs LiteRT-LM >= 0.19.0 on GPU). */
    val LFM3_INT8 = BenchModel(
        key = "lfm3-int8",
        label = "LFM3-1.7B int8, 2k KV",
        kind = Kind.REWRITE,
        fileName = "LFM3-1.7B_int8_ekv2048.litertlm",
        sizeBytes = 1_801_601_024L,
        sha256 = "1e8f4f1436196d410a0fc2bc267c76cd4ca0cec1911e7b2ae16808a4899ff14c",
        license = "LFM Open License v1.0 (not OSI-approved)",
        disableThinking = true,
    )

    val EMBEDDING_270M = BenchModel(
        key = "eg2-270m",
        label = "EmbeddingGemma 2 text 270M (current)",
        kind = Kind.EMBEDDING,
        fileName = ModelCatalog.EMBEDDING_GEMMA_2.fileName,
        sizeBytes = ModelCatalog.EMBEDDING_GEMMA_2.sizeBytes,
        sha256 = ModelCatalog.EMBEDDING_GEMMA_2.sha256,
        license = "Apache-2.0",
    )

    /** litert-community/embeddinggemma-2-740m-litert-lm@66011bd4f00a8b36a1d3c4ae7bf908b4891b4881 (generic CPU/GPU file). */
    val EMBEDDING_740M = BenchModel(
        key = "eg2-740m",
        label = "EmbeddingGemma 2 740M (text+vision+audio)",
        kind = Kind.EMBEDDING,
        fileName = "embeddinggemma-2-740m.litertlm",
        sizeBytes = 484_622_336L,
        sha256 = "e7a8a2204b91e0f96e92960e84a09a89212e1633dcb7575a9bf3378b4df77f4c",
        license = "Apache-2.0",
    )

    val ALL: List<BenchModel> = listOf(GEMMA_4_E2B, LFM3_INT4, LFM3_INT8, EMBEDDING_270M, EMBEDDING_740M)

    fun byKey(key: String?): BenchModel? = ALL.firstOrNull { it.key == key }
}
