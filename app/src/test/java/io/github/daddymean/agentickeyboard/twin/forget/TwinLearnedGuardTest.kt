package io.github.daddymean.agentickeyboard.twin.forget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source-level guards for the twin-memory viewer: private screen, no logs of entry text, reachable, every delete confirmed. */
class TwinLearnedGuardTest {
    private val base = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val activity = File(base, "java/io/github/daddymean/agentickeyboard/TwinLearnedActivity.kt").readText()
    private val ledger = File(base, "java/io/github/daddymean/agentickeyboard/twin/TwinLedger.kt").readText()

    @Test
    fun privateScreenNotExportedAndNoLearningFields() {
        assertTrue(activity.contains("FLAG_SECURE"))
        assertTrue(Regex("EditText\\(this\\)").findAll(activity).count() <= Regex("IME_FLAG_NO_PERSONALIZED_LEARNING").findAll(activity).count())
        val manifest = File(base, "AndroidManifest.xml").readText()
        val decl = Regex("<activity\\s+android:name=\"\\.TwinLearnedActivity\"[^>]*>").find(manifest)!!.value
        assertTrue(decl.contains("android:exported=\"false\""))
        assertFalse(decl.contains("intent-filter"))
    }

    @Test
    fun noEntryTextInLogs() {
        for (src in listOf(activity, ledger)) {
            assertFalse(src.contains("android.util.Log") || Regex("\\bLog\\.[a-z]\\(").containsMatchIn(src) || src.contains("println("))
            // SafeLog calls take only a static message.
            for (m in Regex("SafeLog\\.[a-z]\\(TAG, (\"[^\"]*\")").findAll(src)) assertFalse(m.value.contains("$"))
            assertEquals(Regex("SafeLog\\.").findAll(src).count(), Regex("SafeLog\\.[a-z]\\(TAG, \"[^\"$]*\"").findAll(src).count())
        }
        assertFalse(ledger.contains("SafeLog"))
    }

    @Test
    fun everyDeleteIsConfirmedAndTheScreenIsReachable() {
        // Every delete call is the `run` lambda handed to planThenConfirm, which calls it only from "Delete".
        val forgets = Regex("\\.forget(Keys|App|Range|Phrase)?\\(").findAll(activity).toList()
        assertTrue(forgets.isNotEmpty())
        for (m in forgets) assertTrue(activity.substring(maxOf(0, m.range.first - 200), m.range.first).contains("planThenConfirm("))
        assertTrue(activity.substringAfter("private fun planThenConfirm").contains("setPositiveButton(\"Delete\")"))
        val twin = File(base, "java/io/github/daddymean/agentickeyboard/TwinActivity.kt").readText()
        val memories = File(base, "java/io/github/daddymean/agentickeyboard/TwinMemoryActivity.kt").readText()
        assertTrue(twin.contains("TwinLearnedActivity::class.java") && memories.contains("TwinLearnedActivity::class.java"))
    }

    @Test
    fun otherTwinScreensDropCachesAfterADelete() {
        for (name in listOf("JournalActivity", "TwinMemoryActivity", "TwinActivity")) {
            val src = File(base, "java/io/github/daddymean/agentickeyboard/$name.kt").readText()
            val resume = src.substringAfter("override fun onResume()").substringBefore("\n    }")
            assertTrue("$name checks TwinLedger.generation on resume", resume.contains("TwinLedger.generation"))
        }
    }

    @Test
    fun twinDialogsAreSecureToo() {
        for (name in listOf("TwinLearnedActivity", "TwinMemoryActivity")) {
            val src = File(base, "java/io/github/daddymean/agentickeyboard/$name.kt").readText()
            assertTrue(src.contains("secureTwinWindow()"))
            val shown = Regex("AlertDialog\\.Builder").findAll(src).count()
            assertTrue(shown > 0)
            assertEquals("$name: every AlertDialog uses showSecure()", 0,
                src.lines().count { it.contains(".show()") && !it.contains("Toast.makeText") })
        }
        val helper = File(base, "java/io/github/daddymean/agentickeyboard/twin/SecureDialogs.kt").readText()
        assertTrue(helper.contains("it.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE") && helper.contains("setRecentsScreenshotEnabled(false)"))
    }

    @Test
    fun noExceptionMessageOnScreen() {
        for (name in listOf("TwinLearnedActivity", "TwinMemoryActivity")) {
            val src = File(base, "java/io/github/daddymean/agentickeyboard/$name.kt").readText()
            assertFalse("$name shows an exception message", Regex("\\bit\\.message\\b|\\.localizedMessage|\\{it\\.message").containsMatchIn(src))
        }
    }
}
