package io.github.daddymean.agentickeyboard.twin.memory

import android.content.Context
import io.github.daddymean.agentickeyboard.twin.TwinCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * On-device vector index over approved twin memories. The file is sealed with
 * the twin Keystore key (AES-256-GCM, AAD [AAD]) like [MemoryStore]. The
 * plaintext payload holds only memory ids and float vectors — never memory
 * text — and lives under `noBackupFilesDir/twin/`.
 */
class EncryptedVectorIndex(private val file: File, private val cipher: TwinCipher) {

    data class Entry(val memoryId: String, val vector: FloatArray)

    data class Payload(
        val modelId: String,
        val dim: Int,
        val entries: List<Entry> = emptyList()
    ) {
        fun without(memoryId: String) = copy(entries = entries.filterNot { it.memoryId == memoryId })

        fun upsert(memoryId: String, vector: FloatArray): Payload {
            val rest = entries.filterNot { it.memoryId == memoryId }
            return copy(dim = vector.size, entries = rest + Entry(memoryId, vector))
        }
    }

    @Synchronized
    fun load(): Payload {
        if (!file.exists()) return Payload("", 0, emptyList())
        return DataInputStream(file.inputStream().buffered()).use { inp ->
            val magic = ByteArray(MAGIC.size).also(inp::readFully)
            check(magic.contentEquals(MAGIC)) { "not a vector index" }
            val version = inp.readInt()
            check(version == VERSION) { "unsupported vector index version $version" }
            val iv = ByteArray(inp.readUnsignedByte()).also(inp::readFully)
            val ct = ByteArray(inp.readInt()).also(inp::readFully)
            val plain = cipher.open(TwinCipher.Sealed(iv, ct), AAD)
            try { decode(plain) } finally { plain.fill(0) }
        }
    }

    @Synchronized
    fun save(payload: Payload) {
        val plain = encode(payload)
        val sealed = try { cipher.seal(plain, AAD) } finally { plain.fill(0) }
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { o ->
            o.write(MAGIC)
            o.writeInt(VERSION)
            o.writeByte(sealed.iv.size)
            o.write(sealed.iv)
            o.writeInt(sealed.ciphertext.size)
            o.write(sealed.ciphertext)
        }
        check(tmp.renameTo(file)) { "could not save vector index" }
    }

    @Synchronized
    fun update(change: (Payload) -> Payload): Payload = change(load()).also(::save)

    @Synchronized
    fun delete() {
        file.delete()
        File(file.path + ".tmp").delete()
    }

    fun search(query: FloatArray, memories: List<Memory>, limit: Int): List<SearchHit> {
        if (query.isEmpty() || limit <= 0) return emptyList()
        val byId = memories.associateBy { it.id }
        val payload = load()
        if (payload.dim != 0 && payload.dim != query.size) return emptyList()
        return payload.entries.mapNotNull { e ->
            val memory = byId[e.memoryId] ?: return@mapNotNull null
            if (e.vector.size != query.size) return@mapNotNull null
            SearchHit(memory, cosine(query, e.vector).toDouble(), emptySet())
        }.sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.memory.atMillis })
            .take(limit)
    }

    companion object {
        val MAGIC = "TVEC".toByteArray(Charsets.US_ASCII)
        const val VERSION = 1
        val AAD = "vector-index-v1".toByteArray(Charsets.US_ASCII)

        fun file(context: Context) = File(context.noBackupFilesDir, "twin/memory-vectors.sealed")

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                val x = a[i].toDouble()
                val y = b[i].toDouble()
                dot += x * y
                na += x * x
                nb += y * y
            }
            val denom = sqrt(na) * sqrt(nb)
            return if (denom == 0.0) 0f else (dot / denom).toFloat()
        }

        internal fun encode(payload: Payload): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { o ->
                o.writeUTF(payload.modelId)
                o.writeInt(payload.dim)
                o.writeInt(payload.entries.size)
                for (e in payload.entries) {
                    o.writeUTF(e.memoryId)
                    o.writeInt(e.vector.size)
                    for (v in e.vector) o.writeFloat(v)
                }
            }
            return out.toByteArray()
        }

        internal fun decode(bytes: ByteArray): Payload {
            DataInputStream(ByteArrayInputStream(bytes)).use { inp ->
                val modelId = inp.readUTF()
                val dim = inp.readInt()
                val n = inp.readInt()
                val entries = ArrayList<Entry>(n)
                repeat(n) {
                    val id = inp.readUTF()
                    val len = inp.readInt()
                    val vec = FloatArray(len) { inp.readFloat() }
                    entries += Entry(id, vec)
                }
                return Payload(modelId, dim, entries)
            }
        }
    }
}
