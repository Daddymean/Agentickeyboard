package io.github.daddymean.agentickeyboard.twin.journal

import java.io.File

/** Reads the shipped journal assets and test fixtures from the source tree. */
internal object JournalAssets {
    private fun base(): File = listOf(File("src"), File("app/src")).first { File(it, "main/assets").exists() }
    fun asset(name: String): String = File(base(), "main/assets/twin/$name").readText()
    fun fixture(name: String): List<List<String>> = File(base(), "test/resources/twin/$name").readLines()
        .drop(1).filter { it.isNotBlank() }.map(::csv)
    fun main(path: String): String = File(base(), "main/$path").readText()

    private fun csv(line: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += cur.toString(); cur.setLength(0) }
                else -> cur.append(c)
            }
            i++
        }
        out += cur.toString()
        return out
    }
}
