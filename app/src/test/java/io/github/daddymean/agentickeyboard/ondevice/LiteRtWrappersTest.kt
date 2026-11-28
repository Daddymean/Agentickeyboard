package io.github.daddymean.agentickeyboard.ondevice

import com.google.ai.edge.litertlm.Content
import io.github.daddymean.agentickeyboard.twin.memory.LiteRtTextEmbedder
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pure-JVM tests for the direct Kotlin LiteRT-LM wrappers:
 * - Lazy construction/close never touches JNI native libraries.
 * - Message text extraction concatenates Content.Text spans cleanly.
 * - Source guard ensures reflection bridges do not creep back in.
 */
class LiteRtWrappersTest {

    @Test
    fun wrappersConstructAndCloseLazilyWithoutLoadingNativeLibrary() {
        val fakeModel = File("/nonexistent/model.litertlm")
        val gemma = LiteRtGemmaEngine(fakeModel, null)
        gemma.close()

        val embedder = LiteRtTextEmbedder(fakeModel, null)
        embedder.close()
    }

    @Test
    fun extractContentTextConcatenatesTextPartsAndFallsBackWhenEmpty() {
        val parts = listOf(Content.Text("Hello, "), Content.Text(""), Content.Text("world!"))
        assertEquals("Hello, world!", LiteRtGemmaEngine.extractContentText(parts, "fallback"))
        assertEquals("fallback", LiteRtGemmaEngine.extractContentText(emptyList(), "fallback"))
        assertEquals("fallback", LiteRtGemmaEngine.extractContentText(listOf(Content.Text("")), "fallback"))
    }

    @Test
    fun wrappersUseDirectCallsWithoutReflection() {
        val base = listOf(File("src"), File("app/src")).first { it.exists() }
        val files = listOf(
            File(base, "main/java/io/github/daddymean/agentickeyboard/ondevice/LiteRtGemmaEngine.kt"),
            File(base, "main/java/io/github/daddymean/agentickeyboard/twin/memory/LiteRtTextEmbedder.kt"),
            File(base, "debug/java/io/github/daddymean/agentickeyboard/bench/LiteRtBenchRunner.kt")
        )
        for (file in files) {
            val src = file.readText()
            assertFalse("${file.name} must not use Class.forName", src.contains("Class.forName"))
            assertFalse("${file.name} must not use getDeclaredConstructor", src.contains("getDeclaredConstructor"))
            assertFalse("${file.name} must not use getMethod", src.contains("getMethod("))
        }
    }
}
