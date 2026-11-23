package io.github.daddymean.agentickeyboard.learned

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** KEYBOARD-047 source guards: private screen, every dialog secure, every delete confirmed, no logs of text, no network. */
class KeyboardLearnedGuardTest {
    private val base = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val activity = File(base, "java/io/github/daddymean/agentickeyboard/KeyboardLearnedActivity.kt").readText()
    private val data = File(base, "java/io/github/daddymean/agentickeyboard/learned/KeyboardLearnedData.kt").readText()

    @Test
    fun screenIsSecureAndEveryDialogUsesShowSecure() {
        assertTrue(activity.contains("secureTwinWindow()"))
        assertTrue(Regex("AlertDialog\\.Builder").findAll(activity).count() > 0)
        assertTrue(activity.contains(".showSecure()"))
        assertEquals("every AlertDialog uses showSecure()", 0,
            activity.lines().count { it.contains(".show()") && !it.contains("Toast.makeText") })
        assertFalse(activity.contains(".create()"))
    }

    @Test
    fun notExportedAndReachableFromSettings() {
        val manifest = File(base, "AndroidManifest.xml").readText()
        val decl = Regex("<activity\\s+android:name=\"\\.KeyboardLearnedActivity\"[^>]*>").find(manifest)!!.value
        assertTrue(decl.contains("android:exported=\"false\""))
        assertFalse(decl.contains("intent-filter"))
        val setup = File(base, "java/io/github/daddymean/agentickeyboard/SetupTab.kt").readText()
        assertTrue(setup.contains("KeyboardLearnedActivity::class.java"))
    }

    @Test
    fun fieldsOptOutOfLearningAndTextCantBeCopied() {
        assertTrue(Regex("EditText\\(this\\)").findAll(activity).count() <= Regex("IME_FLAG_NO_PERSONALIZED_LEARNING").findAll(activity).count())
        assertTrue(activity.contains("setTextIsSelectable(false)"))
    }

    @Test
    fun everyDeleteRunsOnlyFromTheConfirmation() {
        val calls = Regex("\\bit\\.(delete|clear|clearAll|deleteCounted|clearCounted)\\(").findAll(activity).toList()
        assertEquals(3, calls.size)
        for (m in calls) assertTrue(activity.substring(maxOf(0, m.range.first - 400), m.range.first).contains("confirm("))
        assertTrue(activity.substringAfter("private fun confirm(").contains("setPositiveButton(\"Delete\")"))
        assertFalse(Regex("data\\(\\)\\.(delete|clear)").containsMatchIn(activity))
    }

    @Test
    fun noTextInLogsOrErrorsAndNoNetwork() {
        for (src in listOf(activity, data)) {
            assertFalse(src.contains("android.util.Log") || Regex("\\bLog\\.[a-z]\\(").containsMatchIn(src) || src.contains("println("))
            assertEquals(Regex("SafeLog\\.").findAll(src).count(), Regex("SafeLog\\.[a-z]\\(TAG, \"[^\"$]*\"").findAll(src).count())
            assertFalse(Regex("\\bit\\.message\\b|\\.localizedMessage").containsMatchIn(src))
            for (net in listOf("java.net", "okhttp3", "retrofit2", "GeminiManager", "supabase")) assertFalse(src.contains(net))
        }
        assertFalse(data.contains("SafeLog"))
    }

    @Test
    fun roomDeletesRunWithSecureDeleteAndCheckpoint() {
        assertTrue(data.contains("PRAGMA secure_delete = ON") && data.contains("PRAGMA wal_checkpoint(TRUNCATE)"))
        val models = File(base, "java/io/github/daddymean/agentickeyboard/db/DatabaseModels.kt").readText()
        assertTrue(models.contains(".addCallback(SECURE_DELETE)"))
        // The IME reloads touch calibration when this screen clears it.
        val vm = File(base, "java/io/github/daddymean/agentickeyboard/ui/KeyboardViewModel.kt").readText()
        assertTrue(vm.contains("KeyboardSettings.KEY_TOUCH_CALIBRATION -> touchCalibration.restore("))
    }
}
