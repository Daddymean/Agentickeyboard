package io.github.daddymean.agentickeyboard.util

import kotlin.math.abs
import kotlin.math.sqrt

data class SwipePoint(val x: Float, val y: Float)

object SwipeToTypeEngine {

    val keyCenters = mapOf(
        'q' to SwipePoint(0.5f, 0.5f), 'w' to SwipePoint(1.5f, 0.5f), 'e' to SwipePoint(2.5f, 0.5f), 'r' to SwipePoint(3.5f, 0.5f), 't' to SwipePoint(4.5f, 0.5f),
        'y' to SwipePoint(5.5f, 0.5f), 'u' to SwipePoint(6.5f, 0.5f), 'i' to SwipePoint(7.5f, 0.5f), 'o' to SwipePoint(8.5f, 0.5f), 'p' to SwipePoint(9.5f, 0.5f),
        
        'a' to SwipePoint(1.0f, 1.5f), 's' to SwipePoint(2.0f, 1.5f), 'd' to SwipePoint(3.0f, 1.5f), 'f' to SwipePoint(4.0f, 1.5f), 'g' to SwipePoint(5.0f, 1.5f),
        'h' to SwipePoint(6.0f, 1.5f), 'j' to SwipePoint(7.0f, 1.5f), 'k' to SwipePoint(8.0f, 1.5f), 'l' to SwipePoint(9.0f, 1.5f),
        
        'z' to SwipePoint(1.8f, 2.5f), 'x' to SwipePoint(2.8f, 2.5f), 'c' to SwipePoint(3.8f, 2.5f), 'v' to SwipePoint(4.8f, 2.5f), 'b' to SwipePoint(5.8f, 2.5f),
        'n' to SwipePoint(6.8f, 2.5f), 'm' to SwipePoint(7.8f, 2.5f)
    )

    // Key centres again, indexed by letter, for the decoder's inner loops (it
    // rescans the dictionary several times a second while a swipe is in flight).
    private val keyX = FloatArray(26) { keyCenters.getValue('a' + it).x }
    private val keyY = FloatArray(26) { keyCenters.getValue('a' + it).y }

    /** A frequency-ranked dictionary (most frequent first) and each word's rank. */
    private class Loaded(val words: List<String>, val ranks: Map<String, Int>)

    // Loaded from res/raw/wordlist.txt at app start. The small built-in list
    // below remains as a fallback (and keeps unit tests hermetic when no
    // dictionary has been loaded). Words and ranks are published together so the
    // decoder never pairs a new word list with the old ranks.
    @Volatile
    private var loaded = Loaded(emptyList(), emptyMap())

    /**
     * Installs a frequency-ranked dictionary (most frequent first). Passing an
     * empty list reverts to the built-in fallback dictionary.
     */
    fun loadDictionary(words: List<String>) {
        val cleaned = words.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.length in 2..12 && it.all { c -> c in 'a'..'z' } }
            .distinct()
            .take(10_000)
            .toList()
        loaded = Loaded(cleaned, cleaned.withIndex().associate { (i, w) -> w to i })
    }

    private val defaultDictionary = listOf(
        "the", "be", "to", "of", "and", "a", "in", "that", "have", "it",
        "for", "not", "on", "with", "he", "as", "you", "do", "at", "this",
        "but", "his", "by", "from", "they", "we", "say", "her", "she", "or",
        "an", "will", "my", "one", "all", "would", "there", "their", "what", "so",
        "up", "out", "if", "about", "who", "get", "which", "go", "me", "when",
        "make", "can", "like", "time", "no", "just", "him", "know", "take", "people",
        "into", "year", "your", "good", "some", "could", "them", "see", "other", "than",
        "then", "now", "look", "only", "come", "its", "over", "think", "also", "back",
        "after", "use", "two", "how", "our", "work", "first", "well", "way", "even",
        "new", "want", "because", "any", "these", "give", "day", "most", "us", "hello",
        "keyboard", "swipe", "gesture", "type", "kotlin", "agent", "private", "secure",
        "awesome", "simple", "computer", "phone", "chat", "love", "great", "world",
        "happy", "today", "write", "right", "words", "history", "style", "amazing",
        "genius", "privacy"
    )

    private fun distance(p1: SwipePoint, p2: SwipePoint): Float {
        val dx = p1.x - p2.x
        val dy = p1.y - p2.y
        return sqrt(dx * dx + dy * dy)
    }

    fun getClosestChar(pt: SwipePoint): Char {
        var minDistance = Float.MAX_VALUE
        var closestChar = 'a'
        for ((char, center) in keyCenters) {
            val dist = distance(pt, center)
            if (dist < minDistance) {
                minDistance = dist
                closestChar = char
            }
        }
        return closestChar
    }

    fun interpolatePath(path: List<SwipePoint>): List<SwipePoint> {
        if (path.size < 2) return path
        val result = mutableListOf<SwipePoint>()
        for (i in 0 until path.size - 1) {
            val p1 = path[i]
            val p2 = path[i + 1]
            val dist = distance(p1, p2)
            val steps = (dist * 12).toInt().coerceAtLeast(1)
            for (step in 0 until steps) {
                val t = step.toFloat() / steps
                result.add(SwipePoint(p1.x + (p2.x - p1.x) * t, p1.y + (p2.y - p1.y) * t))
            }
        }
        result.add(path.last())
        return result
    }

    /**
     * The words one decode pass scans (the user's vocabulary, then the base
     * dictionary) with everything that depends only on the word worked out once:
     * the key centres of its first and last letters and its frequency penalty.
     * Words that cannot be swiped (shorter than two letters, or not starting and
     * ending on a letter key) are left out.
     */
    private class Candidates(
        val userVocabulary: List<String>,
        val base: Loaded,
        val words: Array<String>,
        val firstX: FloatArray,
        val firstY: FloatArray,
        val lastX: FloatArray,
        val lastY: FloatArray,
        val rankPenalty: FloatArray
    )

    @Volatile
    private var cachedCandidates: Candidates? = null

    private fun candidatesFor(userVocabulary: List<String>): Candidates {
        val base = loaded
        cachedCandidates?.let { if (it.base === base && it.userVocabulary == userVocabulary) return it }
        val baseWords = base.words.ifEmpty { defaultDictionary }
        val words = (userVocabulary.map { it.lowercase() } + baseWords).distinct().filter {
            it.length >= 2 && it.first() in 'a'..'z' && it.last() in 'a'..'z'
        }
        // Nudge similar-scoring candidates toward more frequent words. The
        // penalty spans ~0.8 units across the full 10k dictionary; user
        // vocabulary (rank unknown) is treated as highly frequent.
        val built = Candidates(
            userVocabulary = userVocabulary,
            base = base,
            words = words.toTypedArray(),
            firstX = FloatArray(words.size) { keyX[words[it].first() - 'a'] },
            firstY = FloatArray(words.size) { keyY[words[it].first() - 'a'] },
            lastX = FloatArray(words.size) { keyX[words[it].last() - 'a'] },
            lastY = FloatArray(words.size) { keyY[words[it].last() - 'a'] },
            rankPenalty = FloatArray(words.size) { ((base.ranks[words[it]] ?: 0) / 10_000f) * 0.8f }
        )
        cachedCandidates = built
        return built
    }

    fun getSwipeWordMatches(rawPath: List<SwipePoint>, userVocabulary: List<String> = emptyList()): List<String> {
        if (rawPath.isEmpty()) return emptyList()

        val path = interpolatePath(rawPath)
        val pathX = FloatArray(path.size) { path[it].x }
        val pathY = FloatArray(path.size) { path[it].y }
        val startX = pathX[0]
        val startY = pathY[0]
        val endX = pathX[path.size - 1]
        val endY = pathY[path.size - 1]

        val dictionary = candidatesFor(userVocabulary)

        // The set of keys the gesture passed over depends only on the path, so
        // it is worked out once here rather than per candidate.
        val visitedKeyCount = path.mapTo(HashSet()) { getClosestChar(it) }.size

        val candidates = mutableListOf<Pair<String, Float>>()

        for (i in dictionary.words.indices) {
            val startDist = distance(startX, startY, dictionary.firstX[i], dictionary.firstY[i])
            val endDist = distance(endX, endY, dictionary.lastX[i], dictionary.lastY[i])

            // Max distance threshold to start/end points: 2.2 units (roughly 2 key keys away)
            if (startDist > 2.2f || endDist > 2.2f) {
                continue
            }

            val word = dictionary.words[i]
            var score = scoreWord(word, pathX, pathY, visitedKeyCount)
            score += dictionary.rankPenalty[i]

            candidates.add(word to score)
        }

        return candidates.sortedBy { it.second }.map { it.first }
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return sqrt(dx * dx + dy * dy)
    }

    private fun scoreWord(word: String, pathX: FloatArray, pathY: FloatArray, visitedKeyCount: Int): Float {
        var score = 0f
        var pathIdx = 0

        for (char in word) {
            if (char !in 'a'..'z') continue
            val targetX = keyX[char - 'a']
            val targetY = keyY[char - 'a']
            var minDistance = Float.MAX_VALUE
            var minSquared = Float.MAX_VALUE
            var bestIdx = pathIdx

            // Most of the decoder's time goes here. Square roots keep their
            // order, so a point that is not closer in squared distance cannot be
            // closer; the root is taken only for the few that might be.
            for (i in pathIdx until pathX.size) {
                val dx = pathX[i] - targetX
                val dy = pathY[i] - targetY
                val squared = dx * dx + dy * dy
                if (squared < minSquared) {
                    val dist = sqrt(squared)
                    if (dist < minDistance) {
                        minDistance = dist
                        minSquared = squared
                        bestIdx = i
                    }
                }
            }

            if (minDistance == Float.MAX_VALUE) {
                score += 5.0f
            } else {
                score += minDistance
                pathIdx = bestIdx
            }
        }

        val lengthDiff = abs(visitedKeyCount - word.length)
        score += lengthDiff * 0.25f

        return score
    }
}
