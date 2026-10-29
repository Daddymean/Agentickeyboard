package io.github.daddymean.agentickeyboard.util

/**
 * Counts the number of words in a string without regex allocations.
 * A word is considered any sequence of non-whitespace characters.
 */
fun String.wordCount(): Int {
    var count = 0
    var inWord = false
    for (i in indices) {
        if (!this[i].isWhitespace()) {
            if (!inWord) {
                count++
                inWord = true
            }
        } else {
            inWord = false
        }
    }
    return count
}

/**
 * Returns the last word in a string by scanning backwards, avoiding regex allocations.
 */
fun String.lastWordOrNull(): String? {
    val trimmed = this.trimEnd()
    if (trimmed.isEmpty()) return null
    val lastWhitespace = trimmed.indexOfLast { it.isWhitespace() }
    return if (lastWhitespace == -1) trimmed else trimmed.substring(lastWhitespace + 1)
}
