package io.github.daddymean.agentickeyboard.twin.deadman

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** KEYBOARD-027 row 6: no network, share, notification or background path; local folders only. */
class DeadManGuardTest {
    private val base = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val activity = File(base, "java/io/github/daddymean/agentickeyboard/DeadManActivity.kt").readText()
    private val sources = File(base, "java/io/github/daddymean/agentickeyboard/twin/deadman").walkTopDown()
        .filter { it.extension == "kt" }.map { it.readText() }.toList() + activity

    @Test
    fun noNetworkShareOrBackgroundPath() {
        val banned = listOf("java.net", "okhttp", "HttpURLConnection", "Socket", "ACTION_SEND", "createChooser",
            "WorkManager", "AlarmManager", "JobScheduler", "NotificationManager", "DownloadManager", "ClipboardManager", "android.util.Log")
        for (src in sources) for (b in banned) assertFalse("dead-man code uses $b", src.contains(b))
    }

    @Test
    fun localFoldersOnlyAndThePrivateScreen() {
        assertTrue(activity.contains("EXTRA_LOCAL_ONLY"))
        assertTrue(activity.contains("isLocalAuthority(tree.authority)"))
        assertTrue(activity.contains("FLAG_SECURE"))
        val manifest = File(base, "AndroidManifest.xml").readText()
        val decl = Regex("<activity\\s+android:name=\"\\.DeadManActivity\"[^>]*>").find(manifest)!!.value
        assertTrue(decl.contains("android:exported=\"false\""))
    }
}
