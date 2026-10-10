package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KEYBOARD-021 guard: scans production Kotlin in the keyboard app and the context
 * app and fails if any log call could write an exception's text to logcat.
 *
 * Rules, for every `Log.x(...)` / `SafeLog.x(...)` call:
 *  - no `.message`, `.localizedMessage`, `.cause`, `stackTraceToString` inside the call;
 *  - android.util.Log calls take exactly (tag, "string literal"): no Throwable argument
 *    (use SafeLog, which logs only the exception's class name);
 * and `printStackTrace` must not appear anywhere in production code.
 */
class SafeLogGuardTest {

    private fun sourceRoots(): List<File> = listOf(
        // Unit tests run with the module directory as the working directory.
        listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory },
        listOf(File("../context-app/src/main/java"), File("context-app/src/main/java")).first { it.isDirectory },
    )

    @Test
    fun productionLogCallsNeverCarryExceptionText() {
        val files = sourceRoots().flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
            .filterNot { it.name == "SafeLog.kt" }
        assertTrue("expected to scan production sources", files.size > 50)
        val violations = files.flatMap { file -> LogCallScanner.violations(file.readText()).map { "${file.path}: $it" } }
        assertEquals("Log calls that can leak exception text:\n" + violations.joinToString("\n"), emptyList<String>(), violations)
        // The fixed call sites must actually use SafeLog (guards against a vacuous scan).
        val safeLogCalls = files.sumOf { LogCallScanner.calls(it.readText()).count { c -> c.startsWith("SafeLog.") } }
        assertTrue("expected the SafeLog call sites, found $safeLogCalls", safeLogCalls >= 20)
    }

    @Test
    fun scannerFlagsTheOldPatterns() {
        val bad = listOf(
            """Log.e(TAG, "Error in fixGrammar", e)""",
            """Log.w(TAG, "x: ${'$'}{e.message}")""",
            """Log.i(TAG, "x: ${'$'}{t.localizedMessage}")""",
            """Log.w(TAG, e)""",
            """SafeLog.e(TAG, "x ${'$'}{e.message}", e)""",
            """SafeLog.w(TAG, e.stackTraceToString())""",
            """Log.e(TAG, "cause", e.cause)""",
            """e.printStackTrace()""",
        )
        bad.forEach { assertTrue("should flag: $it", LogCallScanner.violations(it).isNotEmpty()) }
    }

    @Test
    fun scannerAllowsSafeForms() {
        val good = listOf(
            """SafeLog.e(TAG, "Error in fixGrammar", e)""",
            """SafeLog.i(TAG, "${'$'}feature model download failed", e)""",
            """Log.w(TAG, "Saved Gemini key could not be decrypted")""",
            """Log.i("App", "Removed ${'$'}removed rules (backed up)")""",
            """Log.w(TAG, "dropping broadcast: ${'$'}{e.javaClass.simpleName}")""",
            """Log.w(TAG, "Unable to open (settings), with commas")""",
        )
        good.forEach { assertEquals("should allow: $it", emptyList<String>(), LogCallScanner.violations(it)) }
    }
}

/** Minimal source scanner: finds log calls and splits their top-level arguments. */
internal object LogCallScanner {
    private val callStart = Regex("""\b(Safe)?Log\.(v|d|i|w|e|wtf)\(""")
    private val forbidden = listOf(".message", ".localizedMessage", ".cause", "stackTraceToString")

    fun calls(source: String): List<String> = callStart.findAll(source).mapNotNull { m ->
        val end = closingParen(source, m.range.last) ?: return@mapNotNull null
        source.substring(m.range.first, end + 1)
    }.toList()

    fun violations(source: String): List<String> {
        val out = mutableListOf<String>()
        if ("printStackTrace" in source) out += "printStackTrace"
        for (call in calls(source)) {
            if (forbidden.any { it in call }) { out += call; continue }
            if (call.startsWith("Log.")) {
                val args = topLevelArgs(call.substring(call.indexOf('(') + 1, call.length - 1))
                if (args.size != 2 || !args[1].trim().startsWith("\"")) out += call
            }
        }
        return out
    }

    /** Index of the ')' that closes the '(' at [open], skipping string literals. */
    private fun closingParen(s: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '"' -> i = skipString(s, i)
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    /** Returns the index of the closing quote of the string starting at [start]. */
    private fun skipString(s: String, start: Int): Int {
        var i = start + 1
        var braces = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' -> i++
                c == '{' && i > 0 && s[i - 1] == '$' -> braces++
                c == '}' && braces > 0 -> braces--
                c == '"' && braces == 0 -> return i
            }
            i++
        }
        return s.length - 1
    }

    private fun topLevelArgs(inner: String): List<String> {
        val args = mutableListOf<String>()
        var depth = 0
        var startArg = 0
        var i = 0
        while (i < inner.length) {
            when (inner[i]) {
                '"' -> i = skipString(inner, i)
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) { args += inner.substring(startArg, i); startArg = i + 1 }
            }
            i++
        }
        if (inner.isNotBlank()) args += inner.substring(startArg)
        return args
    }
}
