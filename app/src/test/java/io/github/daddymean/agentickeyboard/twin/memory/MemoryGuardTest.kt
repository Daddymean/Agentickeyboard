package io.github.daddymean.agentickeyboard.twin.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** KEYBOARD-029–031: memory code stays on the device, and the screen is private. */
class MemoryGuardTest {
    private val base = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val activity = File(base, "java/io/github/daddymean/agentickeyboard/TwinMemoryActivity.kt").readText()
    private val sources = File(base, "java/io/github/daddymean/agentickeyboard/twin/memory").walkTopDown()
        .filter { it.extension == "kt" }.map { it.readText() }.toList() + activity

    @Test
    fun noNetworkCloudOrSharePath() {
        val banned = listOf("java.net", "okhttp", "retrofit", "HttpURLConnection", "Socket", "dev.context", "ContextClient", "Supabase",
            "Gemini", "GenerativeModel", "ACTION_SEND", "createChooser", "ClipboardManager", "WorkManager", "android.util.Log", "databaseBuilder(")
        for (src in sources) for (b in banned) assertFalse("memory code uses $b", src.contains(b))
        assertTrue(sources.any { it.contains("Room.inMemoryDatabaseBuilder") })
    }

    @Test
    fun privateScreenAndNoLearningFields() {
        assertTrue(activity.contains("FLAG_SECURE"))
        assertTrue(Regex("EditText\\(this\\)").findAll(activity).count() <= Regex("IME_FLAG_NO_PERSONALIZED_LEARNING").findAll(activity).count())
        val manifest = File(base, "AndroidManifest.xml").readText()
        val decl = Regex("<activity\\s+android:name=\"\\.TwinMemoryActivity\"[^>]*>").find(manifest)!!.value
        assertTrue(decl.contains("android:exported=\"false\""))
    }
}
