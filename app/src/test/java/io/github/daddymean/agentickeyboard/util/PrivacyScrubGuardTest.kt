package io.github.daddymean.agentickeyboard.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source guards for the Privacy Scrub / commitment-catcher feature: entry
 * points stay off in password, incognito and other sensitive fields, and
 * the new code makes no network calls.
 */
class PrivacyScrubGuardTest {
    private val base = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val layout = File(base, "java/io/github/daddymean/agentickeyboard/ui/AgenticKeyboardLayout.kt").readText()
    private val service = File(base, "java/io/github/daddymean/agentickeyboard/service/AgenticKeyboardService.kt").readText()
    private val viewModel = File(base, "java/io/github/daddymean/agentickeyboard/ui/KeyboardViewModel.kt").readText()
    private val detector = File(base, "java/io/github/daddymean/agentickeyboard/util/CommitmentDetector.kt").readText()
    private val sanitizer = File(base, "java/io/github/daddymean/agentickeyboard/util/CloudTextSanitizer.kt").readText()
    private val dates = File(base, "java/io/github/daddymean/agentickeyboard/util/QuickAnswers.kt").readText()

    @Test
    fun commitmentCatcherUsesShouldOfferAndClearsOnSensitiveFields() {
        assertTrue(
            layout.contains(
                "CommitmentDetector.shouldOffer(isSensitiveField, inPlaygroundMode, viewModel.canSaveNotes)"
            )
        )
        val effect = layout.substringAfter("Debounced local commitment catcher")
            .substringBefore("// --- AI RESULTS")
        assertTrue(effect.contains("detectedCommitment = null"))
        assertTrue(effect.contains("return@LaunchedEffect"))
    }

    @Test
    fun scrubPaletteClipboardAndSmartSelectStayOffInSensitiveFields() {
        assertTrue(layout.contains("visible = showAiActions && !isSensitiveField"))
        assertTrue(layout.contains("visible = showClipboardActions && clipboardText != null && !isSensitiveField"))
        assertTrue(layout.contains("if (isSensitiveField) emptyList() else CommandPalette.matches("))
        val startSmart = service.substringAfter("private fun startSmartSelect()")
            .substringBefore("private fun endSmartSelect")
        assertTrue(startSmart.contains("if (viewModel.isSensitiveField.value) return null"))
        val endSmart = service.substringAfter("private fun endSmartSelect(flickUp: Boolean)")
            .substringBefore("private fun moveSmartSelect")
        assertTrue(endSmart.contains("viewModel.isSensitiveField.value) return emptyList()"))
        val saveNote = viewModel.substringAfter("fun saveNote(text: String): Boolean {")
            .substringBefore("\n    fun ")
        assertTrue(saveNote.contains("if (_isSensitiveField.value) return false"))
    }

    @Test
    fun newFeatureCodeMakesNoNetworkCalls() {
        for (src in listOf(detector, sanitizer, dates)) {
            for (net in listOf(
                "java.net",
                "okhttp3",
                "retrofit2",
                "HttpURLConnection",
                "GeminiManager",
                "ktor"
            )) {
                assertFalse("$net must not appear in local feature code", src.contains(net))
            }
        }
    }
}
