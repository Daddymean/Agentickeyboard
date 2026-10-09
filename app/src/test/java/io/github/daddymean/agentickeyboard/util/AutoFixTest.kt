package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.db.LearnedCorrection
import io.github.daddymean.agentickeyboard.ui.WordCommitResolver
import io.github.daddymean.agentickeyboard.ui.WordReplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KEYBOARD-011: high-confidence auto-fix on space. The acceptance bars from the
 * plan run here on the KEYBOARD-FEATURES-001 harness sets (copied to
 * test resources/autofix), so CI fails if a change breaks them.
 */
class AutoFixTest {
    private fun raw(name: String) = File("src/main/res/raw/$name").readLines().map { it.trim() }.filter { it.isNotEmpty() }
    private fun resource(name: String) = javaClass.classLoader!!.getResource("autofix/$name")!!.readText()
        .lines().filter { it.isNotBlank() }

    private val spelling = LocalSpelling(
        raw("wordlist.txt").take(10_000), raw("spelling_known_only.txt"),
        raw("spelling_modern.txt") + raw("spelling_extra.txt")
    )
    private val rules = listOf(LearnedCorrection(typo = "teh", correction = "the"))

    /** The space path: shortcuts, learned rules, contractions, then auto-fix. */
    private fun space(
        word: String,
        before: String = "",
        personal: Set<String> = emptySet(),
        paused: Boolean = false,
        sensitive: Boolean = false
    ): String? = WordCommitResolver.resolve(
        word, emptyList(), rules, paused, { TextExpansion.ExpandedText(it) }, sensitive,
        autoFix = { AutoFix.fix(it, before, spelling, personal) }
    )?.replacement

    @Test
    fun `clear typos are fixed on space, keeping case and punctuation`() {
        assertEquals("really", space("realy"))
        assertEquals("finally,", space("finaly,"))
        assertEquals("Really", space("Realy", before = ""))
        assertEquals("Really", space("Realy", before = "Wow. "))
        assertEquals("(really", space("(realy"))
        assertEquals("reasoning", space("reasnng"))
        assertEquals("the", space("teh"))
        assertEquals("don't", space("dont"))
        val fix = WordCommitResolver.resolve("realy", emptyList(), emptyList(), false, { TextExpansion.ExpandedText(it) },
            autoFix = { AutoFix.fix(it, "", spelling) })
        assertEquals(WordReplacement("really", fromLearnedRule = false, fromAutoFix = true), fix)
    }

    @Test
    fun `each guard leaves the word as typed`() {
        assertNull("own word", space("realy", personal = setOf("realy")))
        listOf("lol", "gonna", "tho", "bday", "thx", "pls").forEach { assertNull("slang $it", space(it)) }
        assertNull("name mid-sentence", space("Realy", before = "I met "))
        assertNull("URL", space("realy.com"))
        assertNull("URL path", space("example.com/realy"))
        assertNull("email", space("realy@example.com"))
        assertNull("handle", space("@realy"))
        assertNull("hashtag", space("#realy"))
        assertNull("digits", space("realy2"))
        assertNull("ALL CAPS", space("REALY"))
        assertNull("inner capital", space("reaLy"))
        assertNull("real word", space("form"))
        assertNull("paused", space("realy", paused = true))
        assertNull("sensitive", space("realy", sensitive = true))
        assertNull("no clear winner", space("wich"))
        // Nothing fires before the real dictionary has loaded.
        assertNull(AutoFix.fix("realy", "", LocalSpelling(listOf("really", "real"))))
    }

    @Test
    fun `acceptance - 100 typos at least 80 right and at most 2 wrong`() {
        val rows = resource("typos.tsv").map { it.split("\t") }.filterNot { it[0].startsWith("keith") }
        assertEquals(100, rows.size)
        val results = rows.map { (_, typo, expected) -> Triple(typo, expected, space(typo)) }
        val right = results.count { it.third == it.second }
        val wrong = results.filter { it.third != null && it.third != it.second }
        assertTrue("right $right", right >= 80)
        assertTrue("wrong $wrong", wrong.size <= 2)
    }

    @Test
    fun `acceptance - Keith's 16 words`() {
        val rows = resource("typos.tsv").map { it.split("\t") }.filter { it[0].startsWith("keith") }
        assertEquals(16, rows.size)
        val results = rows.map { (_, typo, expected) -> expected to space(typo) }
        assertTrue(results.count { it.first == it.second } >= 14)
        assertEquals(0, results.count { it.second != null && it.second != it.first })
    }

    @Test
    fun `acceptance - real words and slang, at most 1 of 40 changed`() {
        val words = resource("keep_as_typed.txt")
        assertEquals(40, words.size)
        val changed = words.filter { w -> space(w).let { it != null && it != w } }
        assertTrue("changed $changed", changed.size <= 1)
    }

    @Test
    fun `correctly spelled sentences are left alone`() {
        for (file in listOf("sentences.txt", "sentences_heldout.txt", "sentences_heldout2.txt")) {
            val changed = mutableListOf<String>()
            for (sentence in resource(file)) {
                var before = ""
                for (token in sentence.split(" ")) {
                    space(token, before)?.takeIf { it != token }?.let { changed += "$token→$it" }
                    before += "$token "
                }
            }
            assertEquals(file, emptyList<String>(), changed)
        }
    }
}
