package io.github.daddymean.agentickeyboard.util

import kotlin.math.roundToInt

/**
 * A local, advisory comparison between the requests in an incoming message and
 * the topics addressed by a reply draft. Raw source text is deliberately absent
 * from this value so callers cannot accidentally persist it with the result.
 */
data class ReplyCompletenessAssessment(
    val requestCount: Int,
    val answeredCount: Int,
    val missingTopics: List<String>,
    val confidence: Int,
    val advisory: String?
) {
    val shouldWarn: Boolean
        get() = advisory != null
}

/**
 * Conservative, pure-JVM completeness analysis for reply drafts.
 *
 * The coach recognizes explicit questions and direct request clauses, then
 * compares their topic words with the current draft. It intentionally returns
 * null when evidence is weak: a false warning at Send is more disruptive than
 * missing an ambiguous request.
 */
object ReplyCompletenessCoach {

    private data class Obligation(
        val label: String,
        val matchTerms: Set<String>,
        val explicitness: Int,
        val expectsTimeLikeAnswer: Boolean
    )

    private val sentenceBoundary = Regex("(?<=[?!.])\\s+|[\\n;]+")
    private val tokenRegex = Regex("[A-Za-z0-9']+")
    private val quotedSpan = Regex("[\"“][^\"”]+[\"”]")
    private val reportingQuote = Regex(
        "\\b(?:said|asked|wrote|quoted|reads|message says|email says)\\b",
        RegexOption.IGNORE_CASE
    )

    private val politeLead = Regex(
        "^(?:please\\s+|kindly\\s+|can you\\s+|could you\\s+|would you\\s+|will you\\s+)",
        RegexOption.IGNORE_CASE
    )
    private val questionLead = Regex(
        "^(?:can|could|would|will|do|does|did|is|are|was|were|have|has|when|where|what|who|which|how)\\b",
        RegexOption.IGNORE_CASE
    )
    private val yesNoLead = Regex(
        "^(?:can|could|would|will|do|does|did|is|are|was|were|have|has)\\b",
        RegexOption.IGNORE_CASE
    )
    private val directRequestLead = Regex(
        "^(?:please\\s+|kindly\\s+|let me know\\b|tell me\\b|confirm\\b|send\\b|bring\\b|share\\b|check\\b|review\\b|provide\\b|update\\b|explain\\b|attach\\b|forward\\b|call\\b|text\\b|email\\b|schedule\\b|pick up\\b|drop off\\b|remind\\b|include\\b)",
        RegexOption.IGNORE_CASE
    )
    private val requestVerbAhead = Regex(
        "(?:please\\s+)?(?:let me know|tell me|confirm|send|bring|share|check|review|provide|update|explain|attach|forward|call|text|email|schedule|pick up|drop off|remind|include)\\b",
        RegexOption.IGNORE_CASE
    )
    private val secondQuestionAhead = Regex(
        "(?:when|where|what|who|which|how|can|could|would|will|do|does|did|is|are|was|were|have|has)\\b",
        RegexOption.IGNORE_CASE
    )

    private val rhetoricalPatterns = listOf(
        Regex("^(?:who knows|why bother|what's the point|what is the point|seriously|right)\\??$", RegexOption.IGNORE_CASE),
        Regex("\\b(?:don't you think|wouldn't you agree|isn't that obvious|how should i know)\\b", RegexOption.IGNORE_CASE)
    )
    private val casualQuestions = setOf(
        "how are you",
        "how are things",
        "how is it going",
        "how's it going",
        "what is up",
        "what's up",
        "you good"
    )

    private val stopWords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can",
        "could", "did", "do", "does", "for", "from", "has", "have", "how", "i", "if",
        "in", "is", "it", "kindly", "me", "my", "of", "on", "or", "our", "please",
        "should", "that", "the", "their", "them", "they", "this", "to", "us", "was",
        "we", "were", "what", "when", "where", "whether", "which", "who", "why", "will",
        "with", "would", "you", "your"
    )
    private val actionWords = setOf(
        "answer", "attach", "bring", "call", "check", "confirm", "drop", "email", "explain",
        "forward", "include", "know", "pick", "provide", "remind", "review", "schedule", "send",
        "share", "tell", "text", "update"
    )
    private val temporalWords = setOf(
        "time", "date", "day", "deadline", "due", "schedule", "when", "morning", "afternoon",
        "evening", "today", "tomorrow", "monday", "tuesday", "wednesday", "thursday", "friday",
        "saturday", "sunday"
    )

    /** Returns null when the incoming message does not contain reliable multi-request evidence. */
    fun assess(incomingMessage: String, draft: String): ReplyCompletenessAssessment? {
        if (incomingMessage.isBlank() || draft.isBlank()) return null

        val draftTokens = tokenize(draft)
        if (draftTokens.size < 2) return null

        val obligations = extractObligations(stripReportedQuotes(incomingMessage))
            .distinctBy { it.label to it.matchTerms }
        if (obligations.size < 2) return null

        val draftHasTemporalValue = containsTemporalValue(draft, draftTokens)
        val answered = obligations.map { obligation ->
            obligation to isAddressed(obligation, draftTokens, draftHasTemporalValue)
        }
        val answeredCount = answered.count { it.second }
        val missing = answered.filterNot { it.second }.map { it.first.label }.distinct().take(3)

        val confidence = confidenceFor(obligations)
        if (confidence < 60) return null

        val advisory = if (missing.isNotEmpty()) {
            val topicText = missing.joinToString(", ")
            "This reply may answer $answeredCount of ${obligations.size} requests. Check: $topicText."
        } else {
            null
        }

        return ReplyCompletenessAssessment(
            requestCount = obligations.size,
            answeredCount = answeredCount,
            missingTopics = missing,
            confidence = confidence,
            advisory = advisory
        )
    }

    private fun stripReportedQuotes(text: String): String = text.lineSequence()
        .filterNot { it.trimStart().startsWith(">") }
        .joinToString("\n") { line ->
            if (reportingQuote.containsMatchIn(line)) line.replace(quotedSpan, " ") else line
        }

    private fun extractObligations(text: String): List<Obligation> = sentenceBoundary
        .split(text)
        .asSequence()
        .map { it.trim().trimStart('-', '•', '*').trim() }
        .filter { it.isNotBlank() }
        .flatMap { expandSegment(it).asSequence() }
        .mapNotNull(::toObligation)
        .toList()

    private fun expandSegment(segment: String): List<String> {
        val clean = segment.trim()
        if (clean.isBlank()) return emptyList()

        val withoutQuestion = clean.removeSuffix("?").trim()
        val leadRemoved = politeLead.replace(withoutQuestion, "")
        val hasPoliteListLead = leadRemoved != withoutQuestion

        if (hasPoliteListLead) {
            return splitRequestList(leadRemoved)
        }

        if (clean.endsWith("?") && Regex("\\s+and\\s+(?=$secondQuestionAhead)", RegexOption.IGNORE_CASE).containsMatchIn(withoutQuestion)) {
            return Regex("\\s+and\\s+(?=$secondQuestionAhead)", RegexOption.IGNORE_CASE)
                .split(withoutQuestion)
        }

        return listOf(clean)
    }

    private fun splitRequestList(text: String): List<String> {
        val commaParts = text.split(Regex(",\\s*(?:and\\s+)?", RegexOption.IGNORE_CASE))
        return commaParts.flatMap { part ->
            Regex("\\s+and\\s+(?=$requestVerbAhead)", RegexOption.IGNORE_CASE)
                .split(part)
        }.map { it.trim() }.filter { it.isNotBlank() }
    }

    private fun toObligation(raw: String): Obligation? {
        val trimmed = raw.trim().trimEnd('.', '!', '?', ',').trim()
        if (trimmed.isBlank() || isRhetoricalOrCasual(trimmed)) return null

        val isQuestion = raw.trim().endsWith("?") || questionLead.containsMatchIn(trimmed)
        val isDirectRequest = directRequestLead.containsMatchIn(trimmed)
        if (!isQuestion && !isDirectRequest) return null

        val rawTokens = tokenRegex.findAll(trimmed).map { it.value.lowercase() }.toList()
        val contentTokens = rawTokens.filterNot { it in stopWords }
        if (contentTokens.isEmpty()) return null

        val labelTokens = contentTokens.filterNot { it in actionWords }.ifEmpty { contentTokens }
        val label = labelTokens.take(5).joinToString(" ")
        if (label.isBlank()) return null

        val matchTerms = contentTokens.map(::stem).filter { it.length >= 2 }.toSet()
        if (matchTerms.isEmpty()) return null

        val explicitness = when {
            raw.trim().endsWith("?") && isDirectRequest -> 24
            raw.trim().endsWith("?") -> 20
            isDirectRequest -> 18
            else -> 10
        }
        val expectsTime = rawTokens.any { stem(it) in temporalWords } ||
            Regex("\\b(?:what time|when|which day|what date)\\b", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)

        return Obligation(
            label = label,
            matchTerms = matchTerms,
            explicitness = explicitness,
            expectsTimeLikeAnswer = expectsTime
        )
    }

    private fun isRhetoricalOrCasual(text: String): Boolean {
        val normalized = text.lowercase().replace(Regex("[^a-z' ]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (normalized in casualQuestions) return true
        return rhetoricalPatterns.any { it.containsMatchIn(text.trim()) }
    }

    private fun isAddressed(
        obligation: Obligation,
        draftTokens: Set<String>,
        draftHasTemporalValue: Boolean
    ): Boolean {
        var overlap = obligation.matchTerms.count { it in draftTokens }
        if (obligation.expectsTimeLikeAnswer && draftHasTemporalValue) overlap += 1

        val required = when (obligation.matchTerms.size) {
            0 -> Int.MAX_VALUE
            1, 2 -> 1
            3, 4 -> 2
            else -> 3
        }
        return overlap >= required || overlap.toDouble() / obligation.matchTerms.size >= 0.55
    }

    private fun containsTemporalValue(text: String, tokens: Set<String>): Boolean {
        if (tokens.any { it in temporalWords }) return true
        return Regex("\\b(?:[01]?\\d|2[0-3])(?::[0-5]\\d)?\\s*(?:a\\.?m\\.?|p\\.?m\\.?)?\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }

    private fun confidenceFor(obligations: List<Obligation>): Int {
        val explicitAverage = obligations.map { it.explicitness }.average()
        val topicQuality = obligations.map { it.matchTerms.size.coerceAtMost(4) }.average()
        return (42 + obligations.size.coerceAtMost(4) * 7 + explicitAverage * 0.7 + topicQuality * 2)
            .roundToInt()
            .coerceIn(0, 95)
    }

    private fun tokenize(text: String): Set<String> = tokenRegex.findAll(text)
        .map { stem(it.value.lowercase()) }
        .filter { it.length >= 2 && it !in stopWords }
        .toSet()

    private fun stem(token: String): String {
        val clean = token.trim('\'', '’').lowercase()
        if (clean.all(Char::isDigit)) return clean
        return when {
            clean.endsWith("ies") && clean.length > 5 -> clean.dropLast(3) + "y"
            clean.endsWith("ing") && clean.length > 6 -> clean.dropLast(3)
            clean.endsWith("ed") && clean.length > 5 -> clean.dropLast(2)
            clean.endsWith("es") && clean.length > 5 -> clean.dropLast(2)
            clean.endsWith("s") && clean.length > 4 -> clean.dropLast(1)
            else -> clean
        }
    }
}
