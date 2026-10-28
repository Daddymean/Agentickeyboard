package io.github.daddymean.agentickeyboard.util

/**
 * Returns the number of words in this string, where a word is defined as a contiguous
 * sequence of non-whitespace characters. This is significantly faster and allocates
 * less memory than using `split("\\s+".toRegex()).size`.
 */
fun String.wordCount(): Int {
    var count = 0
    var inWord = false
    for (i in 0 until length) {
        if (this[i].isWhitespace()) {
            inWord = false
        } else if (!inWord) {
            count++
            inWord = true
        }
    }
    return count
}
