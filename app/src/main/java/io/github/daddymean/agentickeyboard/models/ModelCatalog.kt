package io.github.daddymean.agentickeyboard.models

/**
 * Official, licence-clean model pins used by this app. IDs are stable and are
 * the keys [ModelManager] stores opt-in against, so Gemma 4 E2B rewrite can
 * add its own [ModelSpec] here (or pass one of its own) without changing the
 * manager API.
 *
 * EmbeddingGemma 2 is the text-only 270M LiteRT-LM CPU/GPU package published by
 * litert-community, Apache-2.0, ungated. The older gated
 * `google/embeddinggemma-300m` (Gemma Terms) is not used.
 */
object ModelCatalog {
    const val EMBEDDING_GEMMA_2_ID = "embeddinggemma-2-text-270m"

    val EMBEDDING_GEMMA_2 = ModelSpec(
        id = EMBEDDING_GEMMA_2_ID,
        fileName = "embeddinggemma-2-text-270m.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/9be6e8b90982095dc05c2bd162e4b954ee4dbac7/embeddinggemma-2-text-270m.litertlm",
        sha256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
        sizeBytes = 164_626_432L,
        license = "Apache-2.0",
        source = "litert-community/embeddinggemma-2-text-270m-litert-lm@9be6e8b90982095dc05c2bd162e4b954ee4dbac7 " +
            "(base model google/embeddinggemma-2, license Apache-2.0)"
    )

    const val GEMMA_4_E2B_ID = "gemma-4-e2b-it-litertlm"

    /** Official generic LiteRT-LM build of Gemma 4 E2B IT, Apache-2.0. Not bundled in the APK. */
    val GEMMA_4_E2B = ModelSpec(
        id = GEMMA_4_E2B_ID,
        fileName = "gemma-4-E2B-it.litertlm",
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/3d48132a00cf76de57d926de30f5c5c5ed9b8c0d/gemma-4-E2B-it.litertlm",
        sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
        sizeBytes = 2_588_147_712L,
        license = "Apache-2.0",
        source = "litert-community/gemma-4-E2B-it-litert-lm@3d48132a00cf76de57d926de30f5c5c5ed9b8c0d"
    )
}
