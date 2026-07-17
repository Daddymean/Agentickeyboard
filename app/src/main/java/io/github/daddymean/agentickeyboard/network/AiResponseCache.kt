package io.github.daddymean.agentickeyboard.network

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.LinkedHashMap

/** Opaque digest used as an in-memory AI response-cache key. */
@JvmInline
internal value class AiCacheKey(val digest: String)

/**
 * Builds cache keys from every input that can change an AI prompt.
 *
 * Inputs are length-prefixed before hashing, preventing ambiguous concatenations,
 * and raw typed text is never retained in the map key.
 */
internal object AiCacheKeys {
    private const val SCHEMA = "ai-cache-v1"

    fun grammar(model: String, personalizationContext: String, text: String): AiCacheKey =
        key("grammar", model, personalizationContext, text)

    fun replies(model: String, intent: String, personalizationContext: String, contextMessage: String): AiCacheKey =
        key("replies", model, intent, personalizationContext, contextMessage)

    fun summary(model: String, personalizationContext: String, text: String): AiCacheKey =
        key("summary", model, personalizationContext, text)

    fun translation(
        model: String,
        sourceLanguage: String,
        targetLanguage: String,
        personalizationContext: String,
        text: String
    ): AiCacheKey = key(
        "translation",
        model,
        sourceLanguage,
        targetLanguage,
        personalizationContext,
        text
    )

    fun rewrite(
        model: String,
        preserveVoice: Boolean,
        targetTone: String,
        personalizationContext: String,
        text: String
    ): AiCacheKey = key(
        "rewrite",
        model,
        preserveVoice.toString(),
        targetTone,
        personalizationContext,
        text
    )

    fun compose(
        model: String,
        preserveVoice: Boolean,
        targetTone: String,
        personalizationContext: String,
        instruction: String
    ): AiCacheKey = key(
        "compose",
        model,
        preserveVoice.toString(),
        targetTone,
        personalizationContext,
        instruction
    )

    fun explanation(model: String, text: String): AiCacheKey =
        key("explanation", model, text)

    fun continuation(
        model: String,
        preserveVoice: Boolean,
        personalizationContext: String,
        text: String
    ): AiCacheKey = key(
        "continuation",
        model,
        preserveVoice.toString(),
        personalizationContext,
        text
    )

    fun tone(model: String, personalizationContext: String, text: String): AiCacheKey =
        key("tone", model, personalizationContext, text)

    private fun key(action: String, vararg inputs: String): AiCacheKey {
        val digest = MessageDigest.getInstance("SHA-256")

        fun update(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            digest.update(bytes)
        }

        update(SCHEMA)
        update(action)
        inputs.forEach(::update)

        return AiCacheKey(
            digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        )
    }
}

/**
 * Small, synchronized, typed LRU cache with time-based expiry.
 *
 * A separate instance is used for each response type, removing the previous
 * shared `Any` map and its unchecked casts. Entries are process-memory only.
 */
internal class AiResponseCache<T>(
    private val maxEntries: Int,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private data class Entry<T>(val value: T, val createdAtMillis: Long)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(ttlMillis > 0) { "ttlMillis must be positive" }
    }

    private val entries = object : LinkedHashMap<AiCacheKey, Entry<T>>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AiCacheKey, Entry<T>>): Boolean =
            size > maxEntries
    }

    fun get(key: AiCacheKey): T? = synchronized(entries) {
        val entry = entries[key] ?: return@synchronized null
        if (clockMillis() - entry.createdAtMillis >= ttlMillis) {
            entries.remove(key)
            null
        } else {
            entry.value
        }
    }

    fun put(key: AiCacheKey, value: T) {
        synchronized(entries) {
            removeExpiredLocked(clockMillis())
            entries[key] = Entry(value, clockMillis())
        }
    }

    internal fun size(): Int = synchronized(entries) { entries.size }

    private fun removeExpiredLocked(nowMillis: Long) {
        entries.entries.removeAll { (_, entry) ->
            nowMillis - entry.createdAtMillis >= ttlMillis
        }
    }

    private companion object {
        const val DEFAULT_TTL_MILLIS = 10 * 60 * 1000L
    }
}
