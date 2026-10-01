package io.github.daddymean.agentickeyboard.util

/**
 * Stops a cloud AI result from being applied over the user's text when it carries
 * redaction markers the user never typed (ADR-0002, issue #78).
 *
 * [CloudTextSanitizer] swaps phone numbers, emails, URLs and similar for markers
 * such as `[REDACTED_PHONE]` before a request leaves the device. Round-trip actions
 * (fix grammar, rewrite, translate, compose, continue) echo those markers back, so
 * applying the result would silently replace the user's real number with a marker.
 * Redaction stays as strict as before; only the write-back is refused.
 */
object RedactionApplyGuard {

    /**
     * Tolerates the small rewrites a model makes to a marker it echoes back: case,
     * spaces for underscores, and padding inside the brackets.
     */
    private val MARKER = Regex("""\[\s*REDACTED[_ ]+([A-Z]+(?:[_ ][A-Z]+)*)\s*]""", RegexOption.IGNORE_CASE)

    /**
     * Marker kinds (e.g. `PHONE`) that occur more often in [result] than in [source].
     * Empty means [result] is safe to apply. A marker the user typed themselves is
     * counted in [source], so it never blocks their own text.
     */
    fun introducedMarkers(source: String, result: String): Set<String> {
        if (result.isEmpty()) return emptySet()
        val inResult = countByKind(result)
        if (inResult.isEmpty()) return emptySet()
        val inSource = countByKind(source)
        return inResult.filter { (kind, count) -> count > (inSource[kind] ?: 0) }.keys.toSortedSet()
    }

    /** User-facing reason Apply was refused, naming what the markers stand in for. */
    fun blockedMessage(kinds: Set<String>): String {
        val names = kinds.joinToString(", ") { it.lowercase().replace('_', ' ') }
        return "Not applied: the AI saw your $names as hidden placeholders. Edit by hand or copy the result."
    }

    private fun countByKind(text: String): Map<String, Int> =
        MARKER.findAll(text)
            .map { it.groupValues[1].uppercase().replace(' ', '_') }
            .groupingBy { it }
            .eachCount()
}
