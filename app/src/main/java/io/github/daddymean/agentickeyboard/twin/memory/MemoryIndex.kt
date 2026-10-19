package io.github.daddymean.agentickeyboard.twin.memory

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.text.Normalizer
import kotlin.math.ln

/** One approved memory in the full-text index. [memoryId] is stored but not tokenised. */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, notIndexed = ["memory_id"])
@Entity(tableName = "memory_fts")
data class MemoryFtsRow(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int,
    @ColumnInfo(name = "memory_id") val memoryId: String,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "prompt") val prompt: String
)

@Dao
interface MemoryFtsDao {
    @Insert
    fun insertAll(rows: List<MemoryFtsRow>)

    @Query("SELECT memory_id FROM memory_fts WHERE memory_fts MATCH :query")
    fun match(query: String): List<String>

    @Query("SELECT COUNT(*) FROM memory_fts")
    fun count(): Int
}

@Database(entities = [MemoryFtsRow::class], version = 1, exportSchema = false)
abstract class MemoryIndexDatabase : RoomDatabase() {
    abstract fun dao(): MemoryFtsDao
}

/** A search result: the memory, its score and the query terms it matched. */
data class SearchHit(val memory: Memory, val score: Double, val terms: Set<String>)

/** Ranks candidate hits; BM25 by default. A later on-device embeddings ranker plugs in here. */
fun interface MemoryRanker {
    fun rank(terms: List<String>, candidates: List<Memory>, corpus: List<Memory>): List<SearchHit>
}

/**
 * KEYBOARD-031: on-device memory search. Room FTS4 (`unicode61`, so case and accents
 * fold) over **approved** memories only. The index is an **in-memory** Room database
 * built from the sealed vault, so no plaintext index file is ever written. Pending
 * candidates and rejected sources are never indexed.
 *
 * The query is never passed to FTS as typed: it is split into letter/digit terms
 * (at most [MAX_TERMS], stopwords dropped). Each term runs as its own prefix
 * `MATCH 'term*'`, so FTS operators, quotes or column filters in the user's text
 * can't change the query. Hits are ranked in Kotlin with BM25, because FTS4 has no
 * built-in ranking.
 *
 * Patterns (not code) from okf-agent-memory's BM25 recall and tigerless-labs/agent-memory
 * (both MIT). FTS5 isn't used: Room doesn't support it, and Android's framework SQLite
 * doesn't guarantee it.
 */
class MemoryIndex private constructor(private val db: MemoryIndexDatabase, private val memories: List<Memory>, private val ranker: MemoryRanker) : AutoCloseable {
    private val byId = memories.associateBy { it.id }

    val size: Int get() = db.dao().count()

    fun search(query: String, limit: Int = 5): List<SearchHit> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        val ids = LinkedHashSet<String>()
        for (t in terms) ids += db.dao().match("$t*")
        val candidates = ids.mapNotNull(byId::get)
        return ranker.rank(terms, candidates, memories).filter { it.score > 0 }.take(limit)
    }

    override fun close() = db.close()

    companion object {
        const val MAX_TERMS = 8
        private val STOP = setOf(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "did", "do", "does", "for", "from", "had", "has", "have",
            "he", "her", "his", "how", "i", "if", "in", "is", "it", "its", "me", "my", "of", "on", "or", "our", "she", "so",
            "that", "the", "their", "them", "there", "they", "this", "to", "was", "we", "were", "what", "when", "where",
            "which", "who", "why", "will", "with", "you", "your", "dad", "daddy", "father", "papa", "about", "think", "say", "said"
        )

        /** Builds the index from the approved memories in [state]. Call off the main thread in the app. */
        fun build(context: Context, state: VaultState, ranker: MemoryRanker = Bm25, allowMainThread: Boolean = false): MemoryIndex {
            val builder = Room.inMemoryDatabaseBuilder(context.applicationContext, MemoryIndexDatabase::class.java)
            if (allowMainThread) builder.allowMainThreadQueries()
            val db = builder.build()
            db.dao().insertAll(state.memories.mapIndexed { i, m -> MemoryFtsRow(i + 1, m.id, m.text, m.prompt.orEmpty()) })
            return MemoryIndex(db, state.memories, ranker)
        }

        /** Letter/digit terms, folded like `unicode61` (lower case, no accents). */
        fun terms(query: String): List<String> =
            tokens(query).filter { it.length >= 2 && it !in STOP }.distinct().take(MAX_TERMS)

        fun tokens(text: String): List<String> =
            Regex("[\\p{L}\\p{Nd}]+").findAll(fold(text)).map { it.value }.toList()

        fun fold(text: String): String =
            Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }

    /** Okapi BM25 (k1 1.2, b 0.75) over memory text, with the prompt at half weight; prefix-matched terms. */
    object Bm25 : MemoryRanker {
        private const val K1 = 1.2
        private const val B = 0.75

        override fun rank(terms: List<String>, candidates: List<Memory>, corpus: List<Memory>): List<SearchHit> {
            if (corpus.isEmpty()) return emptyList()
            val docs = corpus.associate { it.id to (tokens(it.text) to tokens(it.prompt.orEmpty())) }
            val avg = docs.values.map { it.first.size + 0.5 * it.second.size }.average().coerceAtLeast(1.0)
            val df = terms.associateWith { t -> docs.values.count { (a, b) -> a.any { it.startsWith(t) } || b.any { it.startsWith(t) } } }
            return candidates.map { m ->
                val (body, prompt) = docs.getValue(m.id)
                val len = body.size + 0.5 * prompt.size
                var score = 0.0
                val matched = HashSet<String>()
                for (t in terms) {
                    val tf = body.count { it.startsWith(t) } + 0.5 * prompt.count { it.startsWith(t) }
                    if (tf == 0.0) continue
                    matched += t
                    val n = df.getValue(t).toDouble()
                    val idf = ln(1 + (corpus.size - n + 0.5) / (n + 0.5))
                    score += idf * tf * (K1 + 1) / (tf + K1 * (1 - B + B * len / avg))
                }
                SearchHit(m, score, matched)
            }.sortedWith(compareByDescending<SearchHit> { it.score }.thenByDescending { it.memory.atMillis })
        }
    }
}
