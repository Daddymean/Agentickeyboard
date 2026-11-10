package io.github.daddymean.agentickeyboard.twin.memory

/**
 * Local text embeddings for twin memory search. Implementations must run on the
 * device and must not send text off the phone.
 *
 * [EmbeddingGemma2Tasks] are the official asymmetric prefixes from
 * https://developers.google.com/edge/litert-lm/embedding_models
 */
fun interface TextEmbedder {
    fun embed(prefixedText: String): FloatArray

    fun embedQuery(text: String): FloatArray = embed(EmbeddingGemma2Tasks.query(text))

    fun embedDocument(text: String): FloatArray = embed(EmbeddingGemma2Tasks.document(text))
}

object EmbeddingGemma2Tasks {
    const val DOCUMENT_PREFIX = "task: search result | text: "
    const val QUERY_PREFIX = "task: search query | text: "

    fun document(text: String): String = DOCUMENT_PREFIX + text.trim()
    fun query(text: String): String = QUERY_PREFIX + text.trim()
}
