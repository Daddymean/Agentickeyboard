package io.github.daddymean.agentickeyboard.twin.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** KEYBOARD-031 rows 1–3: FTS4 search over approved memories only, in memory, injection-safe. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MemoryIndexTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun mem(id: String, text: String, prompt: String? = null, at: Long = 1) =
        Memory(id, text, SourceRef(id.hashCode().toLong(), "text"), text, prompt, null, at, at, false)

    private val state = VaultState(memories = listOf(
        mem("fish", "Fishing at the lake with Grandpa taught me patience.", "A favourite place?"),
        mem("work", "Work hard, but always come home for dinner.", "Work?"),
        mem("cafe", "Our first date was at a café in Montréal."),
        mem("dinner", "Dinner together matters more than dinner being fancy. Dinner is where we talk.")
    ))

    @Test
    fun findsApprovedMemoriesByWordAndPrefixRanked() {
        MemoryIndex.build(context, state, allowMainThread = true).use { idx ->
            assertEquals(4, idx.size)
            assertEquals("fish", idx.search("What did you learn fishing?").first().memory.id)
            assertEquals("fish", idx.search("fish").first().memory.id) // prefix
            assertEquals(listOf("dinner", "work"), idx.search("dinner").map { it.memory.id }) // BM25: tf ranks
            assertEquals("cafe", idx.search("cafe montreal").first().memory.id) // accents fold both ways
            assertTrue(idx.search("the and of").isEmpty()) // stopwords only
            assertTrue(idx.search("spaceship").isEmpty())
        }
    }

    @Test
    fun pendingAndRejectedAreNeverIndexed() {
        val s = state.copy(rejected = setOf("journal:99:text"))
        val pending = LegacyVault.candidates(emptyList(), s)
        assertTrue(pending.isEmpty())
        MemoryIndex.build(context, VaultState(), allowMainThread = true).use { assertTrue(it.search("fishing").isEmpty()) }
    }

    @Test
    fun indexLivesOnlyInMemory() {
        val before = context.databaseList().toSet()
        MemoryIndex.build(context, state, allowMainThread = true).use { it.search("lake") }
        assertEquals(before, context.databaseList().toSet())
    }

    @Test
    fun querySyntaxCannotBreakOrSteerTheMatch() {
        MemoryIndex.build(context, state, allowMainThread = true).use { idx ->
            for (q in listOf("\"", "*", "lake\" OR \"work", "text:lake", "NEAR(lake work)", "-dinner", "'); DROP TABLE memory_fts; --", "^lake", "a".repeat(5000))) {
                idx.search(q) // must not throw
            }
            assertEquals(setOf("fish"), idx.search("text:lake").map { it.memory.id }.toSet())
            assertEquals(MemoryIndex.MAX_TERMS, MemoryIndex.terms((1..20).joinToString(" ") { "word$it" }).size)
        }
    }
}
