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

    // Frequency-ranked dictionary loaded from res/raw/wordlist.txt at app start.
    // The small built-in list below remains as a fallback (and keeps unit tests
    // hermetic when no dictionary has been loaded).
    @Volatile
    private var loadedDictionary: List<String> = emptyList()

    @Volatile
    private var wordRanks: Map<String, Int> = emptyMap()

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
        loadedDictionary = cleaned
        wordRanks = cleaned.withIndex().associate { (i, w) -> w to i }
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

    @Volatile
    private var lastUserVocabulary: List<String>? = null
    @Volatile
    private var lastBaseDictionary: List<String>? = null
    @Volatile
    private var cachedFullDictionary: List<String> = emptyList()

    fun getSwipeWordMatches(rawPath: List<SwipePoint>, userVocabulary: List<String> = emptyList()): List<String> {
        if (rawPath.isEmpty()) return emptyList()

        val path = interpolatePath(rawPath)
        val startPt = path.first()
        val endPt = path.last()

        val baseDictionary = loadedDictionary.ifEmpty { defaultDictionary }
        val fullDictionary = if (userVocabulary != lastUserVocabulary || baseDictionary !== lastBaseDictionary) {
            val combined = (userVocabulary.map { it.lowercase() } + baseDictionary).distinct()
            lastUserVocabulary = userVocabulary
            lastBaseDictionary = baseDictionary
            cachedFullDictionary = combined
            combined
        } else {
            cachedFullDictionary
        }
        val ranks = wordRanks

        val candidates = mutableListOf<Pair<String, Float>>()

        for (word in fullDictionary) {
            if (word.length < 2) continue

            val firstChar = word.first()
            val lastChar = word.last()

            val firstCenter = keyCenters[firstChar] ?: continue
            val lastCenter = keyCenters[lastChar] ?: continue

            val startDist = distance(startPt, firstCenter)
            val endDist = distance(endPt, lastCenter)

            // Max distance threshold to start/end points: 2.2 units (roughly 2 key keys away)
            if (startDist > 2.2f || endDist > 2.2f) {
                continue
            }

            var score = scoreWord(word, path)

            // Nudge similar-scoring candidates toward more frequent words. The
            // penalty spans ~0.8 units across the full 10k dictionary; user
            // vocabulary (rank unknown) is treated as highly frequent.
            val rank = ranks[word] ?: 0
            score += (rank / 10_000f) * 0.8f

            candidates.add(word to score)
        }

        return candidates.sortedBy { it.second }.map { it.first }
    }

    private fun scoreWord(word: String, path: List<SwipePoint>): Float {
        var score = 0f
        var pathIdx = 0

        for (char in word) {
            val targetCenter = keyCenters[char] ?: continue
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

        val visitedKeys = path.map { getClosestChar(it) }.distinct()
        val lengthDiff = abs(visitedKeys.size - word.length)
        score += lengthDiff * 0.25f

        return score
    }
}
