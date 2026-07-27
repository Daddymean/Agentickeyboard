package io.github.daddymean.agentickeyboard.util

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Aggregate vocabulary evidence used by [VoiceMatchScorer]. */
data class VoiceVocabulary(
    val word: String,
    val count: Int = 1
)

/** A local writing sample. Callers should keep the list bounded. */
data class VoiceSample(
    val text: String,
    val wordCount: Int = 0
)

/**
 * A transient, on-device estimate of how closely generated text resembles the
 * user's learned writing habits. No source or candidate text is retained here.
 */
data class VoiceMatchScore(
    val percent: Int,
    val confidence: Int,
    val label: String,
    val signals: List<String>
)

/**
 * Builds a small style fingerprint in memory from local aggregate vocabulary
 * and bounded writing samples, then compares one candidate against it.
 */
object VoiceMatchScorer {
    private val wordRegex = "[A-Za-z]+(?:'[A-Za-z]+)?".toRegex()
    private val sentenceBreakRegex = "[.!?]+".toRegex()
    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "but", "by", "for",
        "from", "had", "has", "have", "he", "her", "his", "i", "if", "in",
        "is", "it", "its", "me", "my", "of", "on", "or", "our", "she",
        "so", "that", "the", "their", "them", "there", "they", "this", "to",
        "was", "we", "were", "will", "with", "you", "your"
    )

    private data class Metrics(
        val words: List<String>,
        val contentWords: List<String>,
        val wordCount: Int,
        val wordsPerSentence: Double,
        val exclamationRate: Double,
        val questionRate: Double,
        val ellipsisRate: Double,
        val contractionRate: Double
    )

    private data class Component(
        val name: String,
        val similarity: Double,
        val weight: Double,
        val positiveSignal: String,
        val negativeSignal: String
    )

    fun score(
        candidate: String,
        vocabulary: List<VoiceVocabulary>,
        samples: List<VoiceSample>
    ): VoiceMatchScore? {
        val candidateMetrics = metrics(candidate)
        if (candidateMetrics.wordCount < 3) return null

        val normalizedVocabulary = vocabulary
            .asSequence()
            .mapNotNull { item ->
                val normalized = normalizeWord(item.word)
                normalized.takeIf { it.length > 2 && it !in stopWords }
                    ?.let { it to item.count.coerceAtLeast(1) }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, counts) -> counts.sum() }

        val usableSamples = samples
            .asSequence()
            .map { sample -> sample to metrics(sample.text) }
            .filter { (_, sampleMetrics) -> sampleMetrics.wordCount >= 3 }
            .take(30)
            .toList()

        if (normalizedVocabulary.size < 5 && usableSamples.size < 3) return null

        val components = mutableListOf<Component>()

        if (normalizedVocabulary.isNotEmpty()) {
            val lexical = lexicalSimilarity(candidateMetrics.contentWords, normalizedVocabulary)
            components += Component(
                name = "vocabulary",
                similarity = lexical,
                weight = 45.0,
                positiveSignal = "Familiar vocabulary",
                negativeSignal = "Different vocabulary mix"
            )
        }

        if (usableSamples.isNotEmpty()) {
            val sampleMetrics = usableSamples.map { it.second }
            val typicalMessageLength = median(
                usableSamples.map { (sample, measured) ->
                    sample.wordCount.takeIf { it > 0 }?.toDouble() ?: measured.wordCount.toDouble()
                }
            )
            val typicalSentenceLength = median(sampleMetrics.map { it.wordsPerSentence })
            val typicalExclamation = sampleMetrics.map { it.exclamationRate }.average()
            val typicalQuestion = sampleMetrics.map { it.questionRate }.average()
            val typicalEllipsis = sampleMetrics.map { it.ellipsisRate }.average()
            val typicalContractions = sampleMetrics.map { it.contractionRate }.average()

            components += Component(
                name = "length",
                similarity = ratioSimilarity(candidateMetrics.wordCount.toDouble(), typicalMessageLength, floor = 4.0),
                weight = 18.0,
                positiveSignal = "Typical message length",
                negativeSignal = "Different message length"
            )
            components += Component(
                name = "rhythm",
                similarity = ratioSimilarity(candidateMetrics.wordsPerSentence, typicalSentenceLength, floor = 3.0),
                weight = 17.0,
                positiveSignal = "Usual sentence rhythm",
                negativeSignal = "Different sentence rhythm"
            )
            components += Component(
                name = "punctuation",
                similarity = listOf(
                    ratioSimilarity(candidateMetrics.exclamationRate, typicalExclamation, floor = 0.25),
                    ratioSimilarity(candidateMetrics.questionRate, typicalQuestion, floor = 0.25),
                    ratioSimilarity(candidateMetrics.ellipsisRate, typicalEllipsis, floor = 0.15)
                ).average(),
                weight = 10.0,
                positiveSignal = "Typical punctuation",
                negativeSignal = "Different punctuation pattern"
            )
            components += Component(
                name = "contractions",
                similarity = ratioSimilarity(candidateMetrics.contractionRate, typicalContractions, floor = 0.03),
                weight = 10.0,
                positiveSignal = "Familiar conversational cadence",
                negativeSignal = "Different conversational cadence"
            )
        }

        if (components.isEmpty()) return null

        val totalWeight = components.sumOf { it.weight }
        val rawScore = components.sumOf { it.similarity.coerceIn(0.0, 1.0) * it.weight } / totalWeight
        val percent = (rawScore * 100.0).roundToInt().coerceIn(5, 98)

        val vocabularyEvidence = min(
            55,
            normalizedVocabulary.size * 2 + normalizedVocabulary.values.sum().coerceAtMost(200) / 20
        )
        val sampleEvidence = min(45, usableSamples.size * 5)
        val confidence = (vocabularyEvidence + sampleEvidence).coerceIn(1, 100)

        val strongest = components.sortedByDescending { it.similarity }
        val signals = buildList {
            strongest.filter { it.similarity >= 0.72 }.take(2).forEach { add(it.positiveSignal) }
            if (isEmpty()) {
                components.sortedBy { it.similarity }.take(2).forEach { add(it.negativeSignal) }
            }
        }

        val label = when {
            confidence < 35 -> "Early estimate"
            percent >= 88 -> "Signature match"
            percent >= 74 -> "Very much your voice"
            percent >= 60 -> "Mostly your voice"
            percent >= 45 -> "Mixed voice"
            else -> "Different from your usual style"
        }

        return VoiceMatchScore(
            percent = percent,
            confidence = confidence,
            label = label,
            signals = signals
        )
    }

    private fun lexicalSimilarity(
        candidateWords: List<String>,
        vocabulary: Map<String, Int>
    ): Double {
        val uniqueCandidate = candidateWords.distinct()
        if (uniqueCandidate.isEmpty()) return 0.5

        var matchedWeight = 0.0
        var possibleWeight = 0.0
        uniqueCandidate.forEach { word ->
            val profileCount = vocabulary[word]
            val weight = profileCount?.let { 1.0 + ln(it.toDouble() + 1.0) } ?: 1.0
            possibleWeight += weight
            if (profileCount != null) matchedWeight += weight
        }
        return (matchedWeight / possibleWeight).coerceIn(0.0, 1.0)
    }

    private fun metrics(text: String): Metrics {
        val words = wordRegex.findAll(text).map { normalizeWord(it.value) }.filter { it.isNotEmpty() }.toList()
        val contentWords = words.filter { it.length > 2 && it !in stopWords }
        val sentenceCount = sentenceBreakRegex.findAll(text).count().coerceAtLeast(1)
        val lengthScale = max(text.length, 1) / 100.0
        val contractions = words.count { '\'' in it }

        return Metrics(
            words = words,
            contentWords = contentWords,
            wordCount = words.size,
            wordsPerSentence = words.size.toDouble() / sentenceCount,
            exclamationRate = text.count { it == '!' } / lengthScale,
            questionRate = text.count { it == '?' } / lengthScale,
            ellipsisRate = countOccurrences(text, "...") / lengthScale,
            contractionRate = contractions.toDouble() / words.size.coerceAtLeast(1)
        )
    }

    private fun ratioSimilarity(a: Double, b: Double, floor: Double): Double {
        val denominator = max(max(abs(a), abs(b)), floor)
        return (1.0 - abs(a - b) / denominator).coerceIn(0.0, 1.0)
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun normalizeWord(word: String): String = word.lowercase().trim('\'', '’')

    private fun countOccurrences(text: String, target: String): Int {
        if (target.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(target, startIndex = index)
            if (index < 0) return count
            count += 1
            index += target.length
        }
    }
}
