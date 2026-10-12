package io.github.daddymean.agentickeyboard.twin.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * KEYBOARD-025 guards: RECORD_AUDIO is declared once and requested only from the journal,
 * after the rationale; the journal screen is private and secure; transcription is
 * on-device only; the app gains no new network-capable speech path.
 */
class JournalManifestGuardTest {
    private val manifest = JournalAssets.main("AndroidManifest.xml")
    private val activity = JournalAssets.main("java/io/github/daddymean/agentickeyboard/JournalActivity.kt")
    private val transcriber = JournalAssets.main("java/io/github/daddymean/agentickeyboard/twin/journal/OnDeviceTranscriber.kt")

    @Test
    fun microphonePermissionIsDeclaredOnceAndTheJournalIsPrivate() {
        assertEquals(1, Regex("android\\.permission\\.RECORD_AUDIO").findAll(manifest).count())
        val journal = Regex("<activity\\s+android:name=\"\\.JournalActivity\"[^>]*>").find(manifest)!!.value
        assertTrue(journal.contains("android:exported=\"false\""))
        assertFalse(journal.contains("intent-filter"))
        assertTrue(activity.contains("FLAG_SECURE"))
    }

    @Test
    fun permissionIsRequestedOnlyFromTheJournalAfterTheRationale() {
        val src = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val requesters = src.walkTopDown().filter { it.extension == "kt" && it.readText().contains("requestPermissions(") }.map { it.name }.toList()
        assertEquals(listOf("JournalActivity.kt"), requesters)
        val ask = activity.substringAfter("private fun withMicPermission")
        assertTrue(ask.indexOf("MIC_RATIONALE") in 0 until ask.indexOf("requestPermissions("))
        assertTrue(activity.contains("override fun onStop()") && activity.substringAfter("override fun onStop()").substringBefore("}").contains("onStopRecording"))
    }

    @Test
    fun transcriptionIsOnDeviceOnly() {
        assertTrue(transcriber.contains("createOnDeviceSpeechRecognizer"))
        assertFalse(transcriber.contains("createSpeechRecognizer("))
    }
}
