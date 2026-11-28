package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Test

class WordDiffTest {

    @Test
    fun identicalStringsReturnSingleEqualSpan() {
        assertEquals(
            listOf(WordDiff.Span("hello world", WordDiff.Op.EQUAL)),
            WordDiff.diff("hello world", "hello world")
        )
    }

    @Test
    fun highlightsOnlyChangedWordInShortEdit() {
        val orig = "I went too the store"
        val fixed = "I went to the store"

        assertEquals(
            listOf(
                WordDiff.Span("I went ", WordDiff.Op.EQUAL),
                WordDiff.Span("too", WordDiff.Op.DELETE),
                WordDiff.Span(" the store", WordDiff.Op.EQUAL)
            ),
            WordDiff.originalSpans(orig, fixed)
        )
        assertEquals(
            listOf(
                WordDiff.Span("I went ", WordDiff.Op.EQUAL),
                WordDiff.Span("to", WordDiff.Op.INSERT),
                WordDiff.Span(" the store", WordDiff.Op.EQUAL)
            ),
            WordDiff.revisedSpans(orig, fixed)
        )
    }

    @Test
    fun handlesInsertionsAndDeletionsAndReconstructsTextsExactly() {
        val orig = "We should probably meet tomorrow morning"
        val fixed = "Let's meet tomorrow at 9"

        val origSpans = WordDiff.originalSpans(orig, fixed)
        val revSpans = WordDiff.revisedSpans(orig, fixed)

        assertEquals(orig, origSpans.joinToString("") { it.text })
        assertEquals(fixed, revSpans.joinToString("") { it.text })
    }

    @Test
    fun oversizedInputDegradesGracefullyWithoutLargeTable() {
        val bigOrig = (1..200).joinToString(" ") { "word$it" }
        val bigFixed = (1..200).joinToString(" ") { "other$it" }

        assertEquals(
            listOf(
                WordDiff.Span(bigOrig, WordDiff.Op.DELETE),
                WordDiff.Span(bigFixed, WordDiff.Op.INSERT)
            ),
            WordDiff.diff(bigOrig, bigFixed)
        )
    }
}
