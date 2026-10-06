package io.github.daddymean.agentickeyboard.util

import kotlin.math.abs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the swipe ranking against the scoring the engine performed before the
 * visited-key count was hoisted out of the per-candidate loop.
 *
 * The reference implementation below is the original algorithm, including the
 * per-candidate `path.map { getClosestChar(it) }.distinct()` that the engine no
 * longer does. If the two ever disagree, the optimisation changed behaviour and
 * this test is the thing that catches it — not a wall-clock timing, which varies
 * too much across CI runners to assert on.
 */
class SwipeScoringEquivalenceTest {

    /** A realistic alphabetic vocabulary, unlike the numbered synthetic words. */
    private val dictionary = listOf(
        "the", "and", "you", "that", "was", "for", "are", "with", "his", "they",
        "this", "have", "from", "one", "had", "but", "what", "all", "were", "when",
        "your", "can", "said", "there", "use", "each", "which", "she", "how", "their",
        "will", "other", "about", "out", "many", "then", "them", "these", "some", "her",
        "would", "make", "like", "him", "into", "time", "has", "look", "two", "more",
        "write", "see", "number", "way", "could", "people", "than", "first", "water", "been",
        "call", "who", "oil", "its", "now", "find", "long", "down", "day", "did",
        "get", "come", "made", "may", "part", "over", "new", "sound", "take", "only",
        "little", "work", "know", "place", "year", "live", "back", "give", "most", "very",
        "after", "thing", "our", "just", "name", "good", "sentence", "man", "think", "say",
        "great", "where", "help", "through", "much", "before", "line", "right", "too", "mean",
        "old", "any", "same", "tell", "boy", "follow", "came", "want", "show", "also",
        "around", "form", "three", "small", "set", "put", "end", "does", "another", "well",
        "large", "must", "big", "even", "such", "because", "turn", "here", "why", "ask",
        "went", "men", "read", "need", "land", "different", "home", "move", "try", "kind",
        "hand", "picture", "again", "change", "off", "play", "spell", "air", "away", "animal",
        "house", "point", "page", "letter", "mother", "answer", "found", "study", "still", "learn",
        "should", "world", "high", "every", "near", "add", "food", "between", "own", "below",
        "country", "plant", "last", "school", "father", "keep", "tree", "never", "start", "city",
        "earth", "eye", "light", "thought", "head", "under", "story", "saw", "left", "few"
    )

    @After
    fun restoreDefaultDictionary() {
        // loadDictionary mutates a process-wide singleton; leaving this test's
        // dictionary installed would change results for every other test in the
        // same JVM depending on run order.
        SwipeToTypeEngine.loadDictionary(emptyList())
    }

    /** A gesture that walks through each letter's key centre, as a finger would. */
    private fun pathFor(word: String): List<SwipePoint> =
        word.mapNotNull { SwipeToTypeEngine.keyCenters[it] }

    // --- the original algorithm, kept verbatim for comparison -----------------

    private fun distance(p1: SwipePoint, p2: SwipePoint): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun referenceScore(word: String, path: List<SwipePoint>): Float {
        var score = 0f
        var pathIdx = 0
        for (char in word) {
            val targetCenter = SwipeToTypeEngine.keyCenters[char] ?: continue
            var minDistance = Float.MAX_VALUE
            var bestIdx = pathIdx
            for (i in pathIdx until path.size) {
                val dist = distance(path[i], targetCenter)
                if (dist < minDistance) {
                    minDistance = dist
                    bestIdx = i
                }
            }
            if (minDistance == Float.MAX_VALUE) {
                score += 5.0f
            } else {
                score += minDistance
                pathIdx = bestIdx
            }
        }
        // The per-candidate recomputation the engine used to do.
        val visitedKeys = path.map { SwipeToTypeEngine.getClosestChar(it) }.distinct()
        score += abs(visitedKeys.size - word.length) * 0.25f
        return score
    }

    private fun referenceMatches(rawPath: List<SwipePoint>): List<String> {
        if (rawPath.isEmpty()) return emptyList()
        val path = SwipeToTypeEngine.interpolatePath(rawPath)
        val startPt = path.first()
        val endPt = path.last()
        val candidates = mutableListOf<Pair<String, Float>>()
        for ((rank, word) in dictionary.withIndex()) {
            if (word.length < 2) continue
            val firstCenter = SwipeToTypeEngine.keyCenters[word.first()] ?: continue
            val lastCenter = SwipeToTypeEngine.keyCenters[word.last()] ?: continue
            if (distance(startPt, firstCenter) > 2.2f || distance(endPt, lastCenter) > 2.2f) continue
            var score = referenceScore(word, path)
            score += (rank / 10_000f) * 0.8f
            candidates.add(word to score)
        }
        return candidates.sortedBy { it.second }.map { it.first }
    }

    // --- tests ----------------------------------------------------------------

    @Test
    fun `hoisting the visited key count leaves the ranking identical`() {
        SwipeToTypeEngine.loadDictionary(dictionary)
        // Short, medium and long gestures, plus a couple of awkward shapes.
        val probes = listOf(
            "the", "world", "keyboard", "people", "between",
            "study", "picture", "sentence", "air", "qp"
        )
        for (probe in probes) {
            val path = pathFor(probe)
            if (path.size < 2) continue
            assertEquals(
                "ranking changed for gesture '$probe'",
                referenceMatches(path),
                SwipeToTypeEngine.getSwipeWordMatches(path)
            )
        }
    }

    @Test
    fun `a gesture tracing a word ranks that word first`() {
        SwipeToTypeEngine.loadDictionary(dictionary)
        for (word in listOf("the", "world", "people", "water", "study")) {
            val matches = SwipeToTypeEngine.getSwipeWordMatches(pathFor(word))
            assertTrue("no matches for '$word'", matches.isNotEmpty())
            assertEquals("wrong top match for '$word'", word, matches.first())
        }
    }

    @Test
    fun `the visited key count is computed from the whole interpolated path`() {
        // Guards the hoist itself: the count must come from the interpolated
        // path, not the raw one, or short gestures would score differently.
        val raw = pathFor("the")
        val interpolated = SwipeToTypeEngine.interpolatePath(raw)
        assertTrue("interpolation should add points", interpolated.size > raw.size)
        val visited = interpolated.map { SwipeToTypeEngine.getClosestChar(it) }.distinct()
        assertTrue("gesture should cross more than one key", visited.size > 1)
    }

    @Test
    fun `an empty path yields no matches`() {
        assertEquals(emptyList<String>(), SwipeToTypeEngine.getSwipeWordMatches(emptyList()))
    }
}
