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
}
