package io.github.daddymean.agentickeyboard.twin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KEYBOARD-028: twin data (capture, vault, journal, style stats, memories, recordings,
 * export, dead-man state) must never be able to reach context-app's Supabase sync or
 * any other network path. This is a source-level guard, like [TwinBackupExclusionTest].
 *
 * - context-app and core (the sync side) can't name the keyboard app, the twin code or
 *   the twin's files.
 * - The twin code and the twin screens have no network, cloud-AI or `dev.context`
 *   imports.
 * - Only the allowlisted keyboard files use the context service, and none of them may
 *   touch twin code (the service only drives capture and a read-only chip).
 * - Every text field on a twin screen opts out of keyboard learning, which also turns
 *   off our keyboard's "Save note", cloud AI and history for that field.
 */
class TwinCloudIsolationTest {
    private val root: File = listOf(File("."), File("..")).first { File(it, "context-app").isDirectory && File(it, "app").isDirectory }
    private val appMain = File(root, "app/src/main/java/io/github/daddymean/agentickeyboard")
    private val twinScreens = listOf("TwinActivity", "JournalActivity", "TwinExportActivity", "DeadManActivity")
        .map { File(appMain, "$it.kt") }.filter { it.exists() } +
        (appMain.listFiles { f -> f.name.matches(Regex("Twin.*Activity\\.kt")) }?.toList() ?: emptyList())

    private fun kt(dir: File) = dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "ts", "sql", "xml", "kts") }.toList()

    @Test
    fun syncSideCannotNameTheTwin() {
        val syncSide = kt(File(root, "context-app/src")) + kt(File(root, "core/src")) +
            kt(File(root, "supabase")).filter { "node_modules" !in it.path } + listOf(File(root, ".github/workflows/supabase.yml"))
        assertTrue(syncSide.size > 20)
        val banned = listOf("agentickeyboard", "TwinStore", "TwinVault", "TwinCapture", "TwinStyleStats", "JournalStore",
            "MemoryVault", "DeadMan", "twin.db", "journal.db", "deadman.sealed", "memory.sealed", "noBackupFilesDir/twin", ".aktx")
        for (f in syncSide) {
            val text = f.readText()
            for (b in banned) assertFalse("${f.path} mentions $b", text.contains(b, ignoreCase = b.first().isLowerCase()))
        }
    }

    @Test
    fun twinCodeHasNoNetworkOrContextImports() {
        val twin = kt(File(appMain, "twin")) + twinScreens.distinct()
        assertTrue(twin.size > 15)
        val banned = listOf("import java.net", "import javax.net", "import okhttp3", "import retrofit2", "import android.net.http",
            "import dev.context", "ContextClient", "Supabase", "HttpURLConnection", "GeminiApi", "GenerativeModel",
            "CloudRedactionInterceptor", "import com.google.firebase")
        for (f in twin) {
            val text = f.readText()
            for (b in banned) assertFalse("${f.path} uses $b", text.contains(b))
        }
    }

    @Test
    fun onlyAllowlistedKeyboardFilesUseTheContextServiceAndNoneReadTwinData() {
        val users = kt(appMain).filter { it.readText().contains("import dev.context") }.map { it.name }.toSet()
        assertEquals(setOf("AgenticKeyboardService.kt", "KeyboardViewModel.kt", "ContextNotes.kt"), users)
        for (name in listOf("KeyboardViewModel.kt", "ContextNotes.kt")) {
            val f = kt(appMain).single { it.name == name }
            assertFalse("$name imports twin code", f.readText().contains("agentickeyboard.twin."))
        }
        // The service drives twin capture, so it may import twin code, but the only things it
        // does with the context client are the read-only snapshot and closing it.
        val service = kt(appMain).single { it.name == "AgenticKeyboardService.kt" }.readText()
        val calls = Regex("contextClient\\.(\\w+)").findAll(service).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("cachedSnapshot", "isInitialized", "close"), calls)
        // Notes go out only through saveNote, which refuses sensitive fields.
        val vm = kt(appMain).single { it.name == "KeyboardViewModel.kt" }.readText()
        val save = vm.substringAfter("fun saveNote(").substringBefore("\n    }")
        assertTrue(save.contains("if (_isSensitiveField.value) return false"))
        assertEquals(1, Regex("\\.logNote\\(|\\.logEvents?\\(").findAll(vm).count())
    }

    @Test
    fun everyTwinTextFieldOptsOutOfKeyboardLearning() {
        for (f in twinScreens.distinct()) {
            val text = f.readText()
            val fields = Regex("EditText\\(this\\)").findAll(text).count()
            val flagged = Regex("IME_FLAG_NO_PERSONALIZED_LEARNING").findAll(text).count()
            assertTrue("${f.name}: $fields text fields but $flagged no-learning flags", flagged >= fields)
        }
    }
}
