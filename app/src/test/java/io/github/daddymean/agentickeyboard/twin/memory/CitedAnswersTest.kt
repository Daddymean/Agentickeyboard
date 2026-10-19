package io.github.daddymean.agentickeyboard.twin.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** KEYBOARD-029 rows 1–3 and KEYBOARD-030 row 3: cited replies, the guard, and the twin's context. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CitedAnswersTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun mem(id: String, text: String, record: Long) =
        Memory(id, text, SourceRef(record, "text"), text, "What matters?", null, 1_760_000_000_000, 1, false)

    private val state = LegacyVault.setCore(
        VaultState(memories = listOf(mem("m1", "Always come home for dinner.", 11), mem("m2", "Fishing taught me patience.", 12))),
        LegacyVault.ABOUT_MY_KIDS, "I have six kids and I'm proud of every one of them.", 5
    )

    @Test
    fun everyAnswerQuotesAndCitesItsSource() {
        MemoryIndex.build(context, state, allowMainThread = true).use { idx ->
            val r = TwinAnswerer.answer("What did you say about dinner?", state, idx)
            assertTrue(r.grounded)
            assertEquals(CitationGuard.INTRO, r.segments.first().text)
            val cited = r.segments.drop(1)
            assertTrue(cited.isNotEmpty() && cited.all { it.cites.isNotEmpty() })
            assertEquals("m:m1", r.citations.single().ref)
            assertEquals(11L, r.citations.single().journalRecordId) // links to the journal entry
            assertTrue(r.render().contains("“Always come home for dinner.” [1]"))
            // A core block that matches is quoted and cited too.
            val kids = TwinAnswerer.answer("Tell me about your kids", state, idx)
            assertEquals("core:about_my_kids", kids.citations.single().ref)
        }
    }

    @Test
    fun noSourceMeansNoClaim() {
        MemoryIndex.build(context, state, allowMainThread = true).use { idx ->
            val r = TwinAnswerer.answer("What was your favourite car?", state, idx)
            assertFalse(r.grounded)
            assertEquals(listOf(CitationGuard.NO_SOURCE), r.segments.map { it.text })
            assertTrue(r.citations.isEmpty())
        }
    }

    @Test
    fun guardDropsUncitedInventedAndDanglingClaims() {
        val c = listOf(CitationGuard.citationFor("m:m1", state), CitationGuard.citationFor("m:gone", state))
        val reply = CitedReply(listOf(
            ReplySegment("Dad said you should become a lawyer."),                      // uncited "Dad said"
            ReplySegment("I always said “never give up on your dreams”.", listOf(0)),  // quote not in source
            ReplySegment("“Always come home for dinner.”", listOf(1)),                // dangling citation
            ReplySegment("He wanted you home: “always come home for dinner”.", listOf(0)) // verbatim, cited
        ), c, true)
        val out = CitationGuard.enforce(reply, state)
        assertEquals(1, out.segments.size)
        assertEquals(3, out.dropped)
        assertEquals(listOf(0), out.segments.single().cites)
        assertEquals("m:m1", out.citations.single().ref)
        // All uncited: the reply becomes "I don't know".
        val none = CitationGuard.enforce(CitedReply(listOf(ReplySegment("Dad loved jazz.")), emptyList(), true), state)
        assertEquals(listOf(CitationGuard.NO_SOURCE), none.segments.map { it.text })
        assertFalse(none.grounded)
        // A removed memory no longer backs anything.
        val removed = LegacyVault.remove(state, "m1")
        assertFalse(CitationGuard.enforce(CitedReply(listOf(ReplySegment("“Always come home for dinner.”", listOf(0))), listOf(c[0]), true), removed).grounded)
    }

    @Test
    fun modelOutputNeedsResolvableMarkers() {
        val out = CitationGuard.fromModelOutput(
            "Come home for dinner, always. [m:m1] I was a fighter pilot. Patience comes from fishing. [m:m2] Be kind. [m:nope] I have six kids. [core:about_my_kids]",
            state
        )
        assertEquals(listOf("Come home for dinner, always.", "Patience comes from fishing.", "I have six kids."), out.segments.map { it.text })
        assertEquals(listOf("m:m1", "m:m2", "core:about_my_kids"), out.citations.map { it.ref })
        assertEquals(2, out.dropped)
    }

    @Test
    fun coreBlocksAlwaysSitInTheContextWithinBudget() {
        val many = (1..200).map { SearchHit(mem("x$it", "Memory number $it about things.", it.toLong()), 1.0, emptySet()) }
        val ctx = TwinContext.build(state, many, budgetChars = 1500)
        assertTrue(ctx.contains("[core:who_i_am] Who I am: (empty)"))
        assertTrue(ctx.contains("[core:about_my_kids] About my kids: I have six kids"))
        assertTrue(ctx.length <= 1500)
        assertTrue(ctx.contains("[m:x1]"))
        assertFalse(ctx.contains("[m:x200]"))
        val empty = TwinContext.build(state, emptyList(), budgetChars = 10)
        assertTrue(empty.contains("[core:about_my_kids]")) // core blocks even past the budget
    }
}
