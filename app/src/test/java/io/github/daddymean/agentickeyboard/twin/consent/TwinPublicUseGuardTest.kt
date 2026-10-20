package io.github.daddymean.agentickeyboard.twin.consent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KEYBOARD-033 row 3: the twin has no path to a public platform or to anyone's attention.
 * No share sheet, clipboard, file provider, notifications or background scheduling in twin
 * code or on twin screens, and the twin's answer screen is FLAG_SECURE.
 */
class TwinPublicUseGuardTest {
    private val root: File = listOf(File("."), File("..")).first { File(it, "app").isDirectory && File(it, "context-app").isDirectory }
    private val appMain = File(root, "app/src/main/java/io/github/daddymean/agentickeyboard")

    private fun twinCode(): List<File> =
        File(appMain, "twin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() +
            listOf("JournalActivity", "DeadManActivity").map { File(appMain, "$it.kt") }.filter { it.exists() } +
            (appMain.listFiles { f -> f.name.matches(Regex("Twin.*Activity\\.kt")) }?.toList() ?: emptyList())

    @Test
    fun noShareCopyOrNotificationPath() {
        val banned = listOf("ACTION_SEND", "createChooser", "ClipboardManager", "setPrimaryClip", "FileProvider",
            "NotificationManager", "NotificationCompat", "Notification.Builder", "WorkManager", "AlarmManager", "JobScheduler")
        val hits = twinCode().flatMap { f -> banned.filter { f.readText().contains(it) }.map { "${f.name}: $it" } }
        assertTrue("Twin code must not share, copy or notify: $hits", hits.isEmpty())
    }

    @Test
    fun theTwinScreenIsSecureAndLabelled() {
        val screen = File(appMain, "TwinMemoryActivity.kt").readText()
        assertTrue(screen.contains("FLAG_SECURE"))
        assertTrue(screen.contains("TwinUseRules.LABEL"))
        assertTrue(screen.contains("setTextIsSelectable(false)"))
        assertTrue(screen.contains("TwinConsent.mayAsk"))
    }
}
