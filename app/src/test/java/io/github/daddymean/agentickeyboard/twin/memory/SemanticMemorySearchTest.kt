package io.github.daddymean.agentickeyboard.twin.memory

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.models.ModelCatalog
import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import io.github.daddymean.agentickeyboard.twin.TwinPrivacyFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SemanticMemorySearchTest {
    private lateinit var context: Context
    private lateinit var file: File
    private lateinit var cipher: SoftwareTwinCipher
    private lateinit var keyword: MemoryIndex

    private val fish = mem("fish", "Fishing at the lake with Grandpa taught me patience.")
    private val work = mem("work", "Work hard, but always come home for dinner.")
    private val cafe = mem("cafe", "Our first date was at a café in Montréal.")
    private val state = VaultState(memories = listOf(fish, work, cafe))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        file = File(context.noBackupFilesDir, "twin-test/memory-vectors.sealed").apply {
            parentFile?.mkdirs()
            delete()
            File(path + ".tmp").delete()
        }
        cipher = SoftwareTwinCipher()
        keyword = MemoryIndex.build(context, state, allowMainThread = true)
    }

    @After
    fun tearDown() {
        keyword.close()
        file.delete()
        File(file.path + ".tmp").delete()
    }

    @Test
    fun filterRunsBeforeAnythingIsEmbedded() {
        val embedder = CountingEmbedder()
        val search = searcher(embedder, secureApps = setOf("com.bank.app"))
        val password = field(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val incognito = field(ime = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        val sensitive = field(hints = listOf("cvv"))
        val bank = field(pkg = "com.bank.app")
        assertTrue(search.search("Fishing at the lake", field = password).isEmpty())
        assertTrue(search.search("Fishing at the lake", field = incognito).isEmpty())
        assertTrue(search.search("Fishing at the lake", field = sensitive).isEmpty())
        assertTrue(search.search("Fishing at the lake", field = bank).isEmpty())
        assertEquals(0, embedder.calls)
        assertFalse(search.add(fish, password))
        assertEquals(0, embedder.calls)
        assertTrue(search.add(fish))
        assertEquals(1, embedder.calls)
        assertEquals("fish", search.search("Fishing at the lake").first().memory.id)
        assertEquals(2, embedder.calls) // one document, one query
    }

    @Test
    fun indexIsEncryptedAtRestWithNoPlaintext() {
        val marker = "UNIQUE_PLAINTEXT_MARKER_FISHING_LAKE"
        val secretMem = mem("secret", marker)
        val search = searcher(FakeWordEmbedder(), memories = listOf(secretMem))
        assertTrue(search.add(secretMem))
        assertTrue(file.exists())
        val raw = file.readBytes()
        val needle = marker.toByteArray(Charsets.UTF_8)
        assertFalse("plaintext leaked into the index file", raw.containsAt(needle))
        assertFalse("index magic must be followed by ciphertext, not raw ids only", raw.decodeToString().contains(marker))
        // Reload through the cipher and confirm the id (not the text) is what was stored.
        val loaded = EncryptedVectorIndex(file, cipher).load()
        assertEquals(listOf("secret"), loaded.entries.map { it.memoryId })
    }

    @Test
    fun addDeleteAndSearchRankingWithFakeEmbedder() {
        val search = searcher(FakeWordEmbedder())
        assertTrue(search.add(fish))
        assertTrue(search.add(work))
        assertTrue(search.add(cafe))
        assertEquals(listOf("fish", "work"), search.search("fishing patience", 2).map { it.memory.id })
        assertEquals("cafe", search.search("café montréal").first().memory.id)
        search.remove("fish")
        assertEquals("work", search.search("fishing patience").first().memory.id)
        assertFalse(search.search("fishing patience").any { it.memory.id == "fish" })
    }

    @Test
    fun fallsBackToKeywordSearchWhenTheModelIsAbsent() {
        val search = SemanticMemorySearch(
            vectors = EncryptedVectorIndex(file, cipher),
            keyword = keyword,
            memories = state.memories,
            embedder = null,
            modelId = ModelCatalog.EMBEDDING_GEMMA_2_ID
        )
        assertFalse(search.isSemanticReady)
        assertEquals("fish", search.search("What did you learn fishing?").first().memory.id)
        assertFalse(search.add(fish))
        assertFalse(file.exists())
    }

    private fun searcher(
        embedder: TextEmbedder,
        memories: List<Memory> = state.memories,
        secureApps: Set<String> = emptySet()
    ) = SemanticMemorySearch(
        vectors = EncryptedVectorIndex(file, cipher),
        keyword = keyword,
        memories = memories,
        embedder = embedder,
        modelId = ModelCatalog.EMBEDDING_GEMMA_2_ID,
        secureApps = secureApps
    )

    private fun mem(id: String, text: String, at: Long = 1) =
        Memory(id, text, SourceRef(id.hashCode().toLong(), "text"), text, null, null, at, at, false)

    private fun field(
        inputType: Int = InputType.TYPE_CLASS_TEXT,
        ime: Int = 0,
        hints: List<String?> = emptyList(),
        pkg: String? = "com.example.notes"
    ) = TwinPrivacyFilter.Field(inputType, ime, hints, pkg)

    /** Records how many times the embedder saw text; used to prove filters run first. */
    private class CountingEmbedder : TextEmbedder {
        var calls = 0
        private val inner = FakeWordEmbedder()
        override fun embed(prefixedText: String): FloatArray {
            calls++
            return inner.embed(prefixedText)
        }
    }

    /**
     * Deterministic bag-of-words vectors so ranking can be unit-tested without
     * the LiteRT-LM native library or a 165 MB weight file.
     */
    private class FakeWordEmbedder : TextEmbedder {
        override fun embed(prefixedText: String): FloatArray {
            var text = prefixedText
            for (prefix in listOf(EmbeddingGemma2Tasks.DOCUMENT_PREFIX, EmbeddingGemma2Tasks.QUERY_PREFIX)) {
                if (text.startsWith(prefix)) text = text.removePrefix(prefix)
            }
            text = text.lowercase()
            val dim = 16
            val v = FloatArray(dim)
            for (tok in Regex("[\\p{L}\\p{Nd}]+").findAll(MemoryIndex.fold(text)).map { it.value }) {
                if (tok.length < 2) continue
                var h = tok.hashCode()
                val i = Math.floorMod(h, dim)
                v[i] += 1f
                v[(i + 3) % dim] += 0.25f
            }
            val norm = kotlin.math.sqrt(v.fold(0.0) { a, x -> a + x * x }.toFloat())
            if (norm > 0f) for (i in v.indices) v[i] /= norm
            return v
        }
    }

    private fun ByteArray.containsAt(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
