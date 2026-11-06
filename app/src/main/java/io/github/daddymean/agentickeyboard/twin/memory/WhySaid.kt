package io.github.daddymean.agentickeyboard.twin.memory

/**
 * Round 2: "Why did the twin say this?" — turns a [CitedReply] into a readable
 * explanation that lists each statement with the memory/core citations it rests on.
 * On-device only; never invents sources.
 */
object WhySaid {
    data class Line(val statement: String, val sources: List<Citation>)

    data class Explanation(val grounded: Boolean, val lines: List<Line>, val note: String?) {
        fun render(): String = buildString {
            append("Why the twin said this\n\n")
            if (!grounded) {
                append(note ?: CitationGuard.NO_SOURCE)
                return@buildString
            }
            lines.forEachIndexed { i, line ->
                append("${i + 1}. ${line.statement}\n")
                if (line.sources.isEmpty()) {
                    append("   (no citation — kept as a neutral phrase)\n")
                } else {
                    line.sources.forEach { c ->
                        append("   ← ${c.label}")
                        if (c.ref.isNotBlank()) append(" (${c.ref})")
                        append("\n")
                    }
                }
            }
            note?.let { append("\n$it") }
        }.trimEnd()
    }

    fun explain(reply: CitedReply): Explanation {
        if (!reply.grounded) {
            return Explanation(false, emptyList(), CitationGuard.NO_SOURCE)
        }
        val lines = reply.segments.map { seg ->
            Line(
                statement = seg.text.trim(),
                sources = seg.cites.mapNotNull { idx -> reply.citations.getOrNull(idx) },
            )
        }.filter { it.statement.isNotEmpty() }
        val droppedNote = if (reply.dropped > 0) {
            "${reply.dropped} statement(s) were dropped because they had no valid source."
        } else null
        return Explanation(true, lines, droppedNote)
    }
}
