package io.github.daddymean.agentickeyboard.twin.export

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KEYBOARD-026 row 5 (Keith, 2026-10-12 17:12 PT: no network or cloud upload, no
 * automatic sharing): the export code has no network, share or background path.
 */
class TwinExportGuardTest {
    private val base = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val activity = File(base, "java/io/github/daddymean/agentickeyboard/TwinExportActivity.kt").readText()
    private val sources = File(base, "java/io/github/daddymean/agentickeyboard/twin/export").walkTopDown()
        .filter { it.extension == "kt" }.map { it.readText() }.toList() + activity

    @Test
    fun noNetworkShareOrBackgroundPath() {
        val banned = listOf("java.net", "okhttp", "HttpURLConnection", "Socket", "ACTION_SEND", "createChooser",
            "WorkManager", "AlarmManager", "JobScheduler", "DownloadManager", "BackupAgent", "ClipboardManager", "android.util.Log")
        for (src in sources) for (b in banned) assertFalse("export code uses $b", src.contains(b))
    }

    @Test
    fun startsFromTheUserAndAsksForALocalDocument() {
        assertTrue(activity.contains("ACTION_CREATE_DOCUMENT"))
        assertTrue(activity.contains("EXTRA_LOCAL_ONLY"))
        assertTrue(activity.contains("isLocalProvider(uri)"))
        assertTrue(activity.contains("FLAG_SECURE"))
        assertTrue(activity.contains("IME_FLAG_NO_PERSONALIZED_LEARNING"))
        val manifest = File(base, "AndroidManifest.xml").readText()
        val decl = Regex("<activity\\s+android:name=\"\\.TwinExportActivity\"[^>]*>").find(manifest)!!.value
        assertTrue(decl.contains("android:exported=\"false\""))
    }
}
