package io.github.daddymean.agentickeyboard.models

/**
 * A pinned, licence-documented model file that [ModelManager] may download only
 * after the user opts in. [sha256] is the lowercase hex digest of the exact
 * bytes at [downloadUrl] for this pin; a mismatch deletes the file.
 */
data class ModelSpec(
    val id: String,
    val fileName: String,
    val downloadUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val license: String,
    /** Human-readable origin, e.g. a Hugging Face repo and revision. */
    val source: String = "",
)
