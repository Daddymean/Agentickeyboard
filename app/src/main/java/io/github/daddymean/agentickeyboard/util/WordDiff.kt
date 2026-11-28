package io.github.daddymean.agentickeyboard.util

/**
 * Pure-Kotlin word-level diff for the expanded AI result preview.
 *
 * Uses a zero-regex tokenizer and a bounded LCS table ([MAX_TOKENS]) so short
 * edits highlight only the changed spans while huge drafts degrade gracefully
 * without risking frame drops on the IME UI thread.
 */
object WordDiff {
    enum class Op { EQUAL, DELETE, INSERT }

    data class Span(val text: String, val op: Op)

    internal const val MAX_TOKENS = 160

    /** Computes merged word-level diff spans from [original] to [revised]. */
    fun diff(original: String, revised: String): List<Span> {
        if (original == revised) {
            return if (original.isEmpty()) emptyList() else listOf(Span(original, Op.EQUAL))
        }
        if (original.isEmpty()) return listOf(Span(revised, Op.INSERT))
        if (revised.isEmpty()) return listOf(Span(original, Op.DELETE))

        val a = tokenize(original)
        val b = tokenize(revised)
        if (a.size > MAX_TOKENS || b.size > MAX_TOKENS) {
            return listOf(Span(original, Op.DELETE), Span(revised, Op.INSERT))
        }

        val n = a.size
        val m = b.size
        // Flat DP table: dp[i * (m + 1) + j] = LCS length of a[i..] and b[j..]
        val stride = m + 1
        val dp = IntArray((n + 1) * stride)
        for (i in n - 1 downTo 0) {
            val row = i * stride
            val nextRow = (i + 1) * stride
            for (j in m - 1 downTo 0) {
                dp[row + j] = if (a[i] == b[j]) {
                    dp[nextRow + j + 1] + 1
                } else {
                    maxOf(dp[nextRow + j], dp[row + j + 1])
                }
            }
        }

        val raw = ArrayList<Span>(n + m)
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> {
                    raw.add(Span(a[i], Op.EQUAL))
                    i++
                    j++
                }
                dp[(i + 1) * stride + j] >= dp[i * stride + (j + 1)] -> {
                    raw.add(Span(a[i], Op.DELETE))
                    i++
                }
                else -> {
                    raw.add(Span(b[j], Op.INSERT))
                    j++
                }
            }
        }
        while (i < n) raw.add(Span(a[i++], Op.DELETE))
        while (j < m) raw.add(Span(b[j++], Op.INSERT))

        return mergeAdjacent(raw)
    }

    /** Spans belonging to [original] (`EQUAL` and `DELETE` only). */
    fun originalSpans(original: String, revised: String): List<Span> =
        mergeAdjacent(diff(original, revised).filter { it.op != Op.INSERT })

    /** Spans belonging to [revised] (`EQUAL` and `INSERT` only). */
    fun revisedSpans(original: String, revised: String): List<Span> =
        mergeAdjacent(diff(original, revised).filter { it.op != Op.DELETE })

    /**
     * Splits [text] into runs of whitespace vs. non-whitespace tokens without
     * invoking the regex engine.
     */
    internal fun tokenize(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val tokens = ArrayList<String>()
        var start = 0
        var inSpace = text[0].isWhitespace()
        for (i in 1 until text.length) {
            val space = text[i].isWhitespace()
            if (space != inSpace) {
                tokens.add(text.substring(start, i))
                start = i
                inSpace = space
            }
        }
        tokens.add(text.substring(start))
        return tokens
    }

    private fun mergeAdjacent(spans: List<Span>): List<Span> {
        if (spans.size <= 1) return spans
        val merged = ArrayList<Span>(spans.size)
        for (span in spans) {
            val last = merged.lastOrNull()
            if (last != null && last.op == span.op) {
                merged[merged.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                merged.add(span)
            }
        }
        return merged
    }
}
