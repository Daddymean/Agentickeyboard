package io.github.daddymean.agentickeyboard.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KEYBOARD-024 guard (re-review on 90f8f3e, r4238828510): every path that commits
 * text into the editor must say whether that text was typed, so a new
 * multi-character insertion path cannot be counted as Keith's typing by accident.
 *
 * Scans the keyboard's production Kotlin (outside the twin package and the commit
 * helpers themselves). Rules:
 *  - `commitText(`, `commitTextWithCaret(`, `commitTyped(`, `commitInserted(`,
 *    `onInsertText(` and `onTypedWord(` calls carry a `// twin:` classification on
 *    the same line or within the three lines above;
 *  - `onKeyPress(` (the typed-key callback) is called only with a one-character
 *    string literal, or with a `// twin: single key` marker.
 * The wrapper also treats any untagged multi-character commit as an insertion
 * (TwinTrackingInputConnectionTest), so this guard is the second line of defence.
 */
class TwinInsertionPathGuardTest {

    companion object {
        fun mainRoot(): File = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }

        fun layoutSource(): String =
            mainRoot().walkTopDown().single { it.name == "AgenticKeyboardLayout.kt" }.readText()

        private val COMMIT = Regex("""\b(commitText|commitTextWithCaret|commitTyped|commitInserted|onInsertText|onTypedWord)\(""")
        private val KEY_PRESS = Regex("""\bonKeyPress\(""")
        private val SINGLE_LITERAL = Regex("""\bonKeyPress\("(\\.|[^"\\])"\)""")

        /** Violations in one file's text, as "line N: code". */
        fun violations(text: String): List<String> {
            val lines = text.lines()
            val out = ArrayList<String>()
            lines.forEachIndexed { i, raw ->
                val line = raw.substringBefore("//").trim()
                if (line.startsWith("*") || line.startsWith("/*")) return@forEachIndexed
                val tagged = (maxOf(0, i - 3)..i).any { "// twin:" in lines[it] }
                // Declarations (`fun commitTyped(`, `override fun commitText(`, parameters) are not calls.
                val isDecl = Regex("""\bfun\s+(\w+\.)?(commitText|commitTextWithCaret|commitTyped|commitInserted)\(""").containsMatchIn(line) ||
                    Regex("""\b(onKeyPress|onInsertText|onTypedWord)\s*:""").containsMatchIn(line)
                if (isDecl) return@forEachIndexed
                if (COMMIT.containsMatchIn(line) && !tagged) out += "line ${i + 1}: ${raw.trim()}"
                if (KEY_PRESS.containsMatchIn(line)) {
                    val allSingle = KEY_PRESS.findAll(line).count() == SINGLE_LITERAL.findAll(line).count()
                    if (!allSingle && "// twin: single key" !in raw) out += "line ${i + 1}: ${raw.trim()}"
                }
            }
            return out
        }
    }

    @Test
    fun everyCommitPathIsClassifiedForTheTwin() {
        val files = mainRoot().walkTopDown()
            .filter { it.extension == "kt" }
            .filterNot { "${File.separator}twin${File.separator}" in it.path }
            .filterNot { it.name == "TypedCommit.kt" || it.name == "CaretCommit.kt" }
            .toList()
        assertTrue("expected to scan production sources", files.size > 50)
        val violations = files.flatMap { f -> violations(f.readText()).map { "${f.path}: $it" } }
        assertEquals(
            "Unclassified text-commit paths (route non-typed text through onInsertText / commitInserted, " +
                "typed multi-character text through onTypedWord / commitTyped, and tag with // twin:):\n" +
                violations.joinToString("\n"),
            emptyList<String>(), violations
        )
        // Non-vacuous: the known insertion sites are present and routed as insertions.
        val layout = layoutSource()
        listOf("onInsertText(clipboardText", "onInsertText(reply)", "onInsertText(emoji)", "onInsertText(separator + continuation",
            "conn.commitInserted(\"\$suggestion \"").forEach {
            assertTrue("expected insertion routing: $it", it in layout)
        }
    }

    @Test
    fun guardFlagsAnUntaggedMultiCharacterPath() {
        val bad = listOf(
            """onKeyPress(clipboardText ?: "")""",
            """onKeyPress(reply)""",
            """onKeyPress("hi")""",
            """conn.commitText("${'$'}suggestion ", 1)""",
            """currentInputConnection?.commitText(item.content, 1)""",
            """onInsertText(text)""",
            """ic.commitTextWithCaret(text, offset)""",
        )
        bad.forEach { assertTrue("should flag: $it", violations(it).isNotEmpty()) }
        val good = listOf(
            """onKeyPress(" ")""",
            """onKeyPress(",")""",
            """onKeyPress(keyText) // twin: single key""",
            """onInsertText(reply) // twin: inserted""",
            "// twin: typed (swipe word)\nonTypedWord(word)",
            """fun InputConnection.commitTyped(text: CharSequence, pos: Int): Boolean =""",
            """onKeyPress: (String) -> Unit = {},""",
        )
        good.forEach { assertTrue("should allow: $it", violations(it).isEmpty()) }
    }
}
