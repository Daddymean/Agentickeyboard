package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.db.CustomCommand
import io.github.daddymean.agentickeyboard.db.SavedSnippet
import io.github.daddymean.agentickeyboard.db.ShortcutTemplate
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

/** What selecting a vault result will do in the keyboard UI. */
enum class SnippetVaultAction {
    INSERT_TEXT,
    RUN_REWRITE
}

/** Original local store represented by a unified vault entry. */
enum class SnippetVaultSource {
    SAVED_SNIPPET,
    SHORTCUT,
    CUSTOM_COMMAND
}

/**
 * Stable, pure-Kotlin search contract spanning saved snippets, legacy shortcuts,
 * and custom rewrite commands without changing any of their current behavior.
 */
data class SnippetVaultEntry(
    val stableId: String,
    val title: String,
    val content: String,
    val aliases: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val action: SnippetVaultAction = SnippetVaultAction.INSERT_TEXT,
    val source: SnippetVaultSource = SnippetVaultSource.SAVED_SNIPPET,
    val usageCount: Int = 0,
    val lastUsedAt: Long = 0L,
    val updatedAt: Long = 0L
)

enum class SnippetRecallCommand(val token: String) {
    VAULT("/v"),
    FIND("/find")
}

/**
 * A recall query may optionally carry rewrite source text after `::`.
 *
 * Example: `/v boss :: move tomorrow's meeting to 3` searches for "boss" and,
 * after an explicit result tap, runs the chosen custom rewrite against the text
 * after the delimiter. Without a body, choosing a command only stages its normal
 * slash token so nothing executes unexpectedly.
 */
data class SnippetRecallRequest(
    val command: SnippetRecallCommand,
    val query: String,
    val body: String = ""
)

enum class SnippetMatchField {
    TITLE,
    ALIAS,
    TAG,
    CONTENT
}

data class SnippetVaultMatch(
    val entry: SnippetVaultEntry,
    val score: Int,
    val matchedOn: Set<SnippetMatchField>
)

/** Explicit result-tap behavior, kept pure so the IME never improvises actions. */
sealed interface SnippetVaultSelectionPlan {
    data class InsertText(val text: String) : SnippetVaultSelectionPlan
    data class StageCommand(val token: String) : SnippetVaultSelectionPlan
    data class RunRewrite(
        val sourceText: String,
        val instruction: String
    ) : SnippetVaultSelectionPlan
}

object SnippetVaultSelectionPlanner {
    fun plan(
        request: SnippetRecallRequest,
        entry: SnippetVaultEntry
    ): SnippetVaultSelectionPlan = when (entry.action) {
        SnippetVaultAction.INSERT_TEXT -> SnippetVaultSelectionPlan.InsertText(entry.content)
        SnippetVaultAction.RUN_REWRITE -> {
            val body = request.body.trim()
            if (body.isNotEmpty() && entry.content.isNotBlank()) {
                SnippetVaultSelectionPlan.RunRewrite(
                    sourceText = body,
                    instruction = entry.content
                )
            } else {
                SnippetVaultSelectionPlan.StageCommand(entry.title.trim())
            }
        }
    }
}

/** Newline-delimited storage for aliases and tags. Empty and duplicate values disappear. */
object SnippetListCodec {
    fun encode(values: Iterable<String>): String {
        val seen = linkedSetOf<String>()
        val cleaned = mutableListOf<String>()
        values.forEach { raw ->
            val value = raw.trim()
            if (value.isEmpty()) return@forEach
            val key = SnippetVaultSearch.normalize(value)
            if (key.isNotEmpty() && seen.add(key)) cleaned += value
        }
        return cleaned.joinToString("\n")
    }

    fun decode(encoded: String): List<String> = encode(encoded.lineSequence().asIterable())
        .lineSequence()
        .filter { it.isNotEmpty() }
        .toList()
}

/** Deterministic, bounded in-memory recall suitable for the IME process. */
object SnippetVaultSearch {
    private const val DEFAULT_LIMIT = 6
    private const val MAX_RESULTS = 20
    private const val MAX_QUERY_CHARS = 120
    private const val MAX_REWRITE_BODY_CHARS = 4_000
    private const val MAX_SEARCHABLE_CONTENT_CHARS = 4_000
    private const val REWRITE_BODY_DELIMITER = "::"

    private val combiningMarks = "\\p{M}+".toRegex()
    private val nonSearchCharacters = "[^\\p{L}\\p{N}]+".toRegex()
    private val whitespace = "\\s+".toRegex()

    /** Parses only the dedicated recall commands; existing palette tokens remain untouched. */
    fun parseRecall(text: String): SnippetRecallRequest? {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith('/')) return null
        val token = trimmed.takeWhile { !it.isWhitespace() }.lowercase(Locale.ROOT)
        val command = SnippetRecallCommand.entries.firstOrNull { it.token == token } ?: return null
        val remainder = trimmed.drop(token.length).trim()
        val parts = remainder.split(REWRITE_BODY_DELIMITER, limit = 2)
        val query = parts.firstOrNull().orEmpty().trim().take(MAX_QUERY_CHARS)
        val body = parts.getOrNull(1).orEmpty().trim().take(MAX_REWRITE_BODY_CHARS)
        return SnippetRecallRequest(command, query, body)
    }

    fun search(
        entries: List<SnippetVaultEntry>,
        query: String,
        limit: Int = DEFAULT_LIMIT
    ): List<SnippetVaultMatch> {
        val boundedLimit = limit.coerceIn(0, MAX_RESULTS)
        if (boundedLimit == 0 || entries.isEmpty()) return emptyList()

        val normalizedQuery = normalize(query.take(MAX_QUERY_CHARS))
        val queryTokens = tokens(normalizedQuery)

        return entries.asSequence()
            .mapNotNull { entry -> score(entry, normalizedQuery, queryTokens) }
            .sortedWith(
                compareByDescending<SnippetVaultMatch> { it.score }
                    .thenByDescending { it.entry.usageCount.coerceAtLeast(0) }
                    .thenByDescending { it.entry.lastUsedAt.coerceAtLeast(0L) }
                    .thenByDescending { it.entry.updatedAt.coerceAtLeast(0L) }
                    .thenBy { sourcePriority(it.entry.source) }
                    .thenBy { normalize(it.entry.title) }
                    .thenBy { it.entry.stableId }
            )
            .take(boundedLimit)
            .toList()
    }

    internal fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD)
        return decomposed
            .replace(combiningMarks, "")
            .lowercase(Locale.ROOT)
            .replace(nonSearchCharacters, " ")
            .replace(whitespace, " ")
            .trim()
    }

    private fun score(
        entry: SnippetVaultEntry,
        query: String,
        queryTokens: Set<String>
    ): SnippetVaultMatch? {
        val title = normalize(entry.title)
        val aliases = entry.aliases.map(::normalize).filter(String::isNotEmpty)
        val tags = entry.tags.map(::normalize).filter(String::isNotEmpty)
        val content = normalize(entry.content.take(MAX_SEARCHABLE_CONTENT_CHARS))

        if (query.isEmpty()) {
            val popularity = min(entry.usageCount.coerceAtLeast(0), 100)
            return SnippetVaultMatch(entry, 100 + popularity, emptySet())
        }

        var score = 0
        val fields = linkedSetOf<SnippetMatchField>()

        when {
            title == query -> {
                score += 1_200
                fields += SnippetMatchField.TITLE
            }
            query in aliases -> {
                score += 1_180
                fields += SnippetMatchField.ALIAS
            }
            title.startsWith(query) -> {
                score += 900
                fields += SnippetMatchField.TITLE
            }
            aliases.any { it.startsWith(query) } -> {
                score += 880
                fields += SnippetMatchField.ALIAS
            }
            query in title -> {
                score += 760
                fields += SnippetMatchField.TITLE
            }
            aliases.any { query in it } -> {
                score += 740
                fields += SnippetMatchField.ALIAS
            }
        }

        val titleTokens = tokens(title)
        val aliasTokens = aliases.flatMapTo(linkedSetOf()) { tokens(it) }
        val tagTokens = tags.flatMapTo(linkedSetOf()) { tokens(it) }
        val contentTokens = tokens(content)

        if (queryTokens.isNotEmpty() && titleTokens.containsAll(queryTokens)) {
            score += 620
            fields += SnippetMatchField.TITLE
        }
        if (queryTokens.isNotEmpty() && aliasTokens.containsAll(queryTokens)) {
            score += 600
            fields += SnippetMatchField.ALIAS
        }
        if (queryTokens.isNotEmpty() && tagTokens.containsAll(queryTokens)) {
            score += 480
            fields += SnippetMatchField.TAG
        } else if (queryTokens.any { it in tagTokens }) {
            score += 220
            fields += SnippetMatchField.TAG
        }
        if (query in content) {
            score += 340
            fields += SnippetMatchField.CONTENT
        } else if (queryTokens.isNotEmpty() && contentTokens.containsAll(queryTokens)) {
            score += 260
            fields += SnippetMatchField.CONTENT
        }

        if (fields.isEmpty()) return null
        score += min(entry.usageCount.coerceAtLeast(0), 100)
        return SnippetVaultMatch(entry, score, fields)
    }

    private fun tokens(value: String): Set<String> = value
        .split(' ')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toCollection(linkedSetOf())

    private fun sourcePriority(source: SnippetVaultSource): Int = when (source) {
        SnippetVaultSource.SAVED_SNIPPET -> 0
        SnippetVaultSource.SHORTCUT -> 1
        SnippetVaultSource.CUSTOM_COMMAND -> 2
    }
}

fun SavedSnippet.toVaultEntry(): SnippetVaultEntry = SnippetVaultEntry(
    stableId = "snippet:$id",
    title = title,
    content = content,
    aliases = SnippetListCodec.decode(aliases),
    tags = SnippetListCodec.decode(tags),
    action = SnippetVaultAction.INSERT_TEXT,
    source = SnippetVaultSource.SAVED_SNIPPET,
    usageCount = usageCount,
    lastUsedAt = lastUsedAt,
    updatedAt = updatedAt
)

fun ShortcutTemplate.toVaultEntry(): SnippetVaultEntry = SnippetVaultEntry(
    stableId = "shortcut:$id",
    title = shortcut,
    content = template,
    aliases = listOf(shortcut),
    action = SnippetVaultAction.INSERT_TEXT,
    source = SnippetVaultSource.SHORTCUT
)

fun CustomCommand.toVaultEntry(): SnippetVaultEntry = SnippetVaultEntry(
    stableId = "command:$id",
    title = token,
    content = instruction,
    aliases = listOf(token.removePrefix("/")),
    action = SnippetVaultAction.RUN_REWRITE,
    source = SnippetVaultSource.CUSTOM_COMMAND
)

fun SnippetVaultEntry.savedSnippetIdOrNull(): Int? {
    if (source != SnippetVaultSource.SAVED_SNIPPET) return null
    return stableId.removePrefix("snippet:").toIntOrNull()
}
