package io.github.daddymean.agentickeyboard.twin.journal

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import io.github.daddymean.agentickeyboard.twin.memory.LegacyVault
import io.github.daddymean.agentickeyboard.twin.memory.VaultState
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** KEYBOARD-037: handwriting kept as strokes and PNG in an encrypted answer; recognition gated on Keith's approval. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HandwritingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun sample() = InkStrokes(800, 400).apply {
        down(10f, 10f, 1000); move(50f, 60f, 1010); up(90f, 20f, 1020)
        down(200f, 200f, 1100); up(200f, 200f, 1101)
    }

    @Test
    fun strokesRoundTripAndRenderToPng() {
        val ink = sample()
        assertEquals(2, ink.strokes.size)
        assertEquals(0f, ink.strokes[0][2]) // times are relative
        val back = InkStrokes.fromJson(JSONObject(ink.toJson().toString()))
        assertEquals(ink.strokes.size, back.strokes.size)
        assertArrayEquals(ink.strokes[0], back.strokes[0], 0.05f)
        val png = ink.png()
        assertArrayEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), png.copyOf(4))
    }

    @Test
    fun answerIsEncryptedAndOnlyTheTextIsLearned() {
        assertThrows(IllegalArgumentException::class.java) { InkStrokes.answerBody("LI-001", null, "q", "c", InkStrokes(10, 10), "x", null) }
        val body = InkStrokes.answerBody("LI-001", "Kid 2", "What did you love?", "love", sample(), "WillowKettle mornings", null)
        assertEquals("handwriting", body.getString("answer_mode"))
        assertEquals("typed by Keith", body.getString("ink_text_source"))
        val dir = File(context.noBackupFilesDir, "ink-test").also { it.deleteRecursively() }
        val store = JournalStore(context, dir, SoftwareTwinCipher())
        try {
            store.add(JournalKinds.ANSWER, body, 1)
            val raw = dir.walkTopDown().filter { it.isFile }.joinToString { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertFalse(raw.contains("WillowKettle"))
            assertFalse(raw.contains("strokes"))
            val r = store.all().single()
            assertTrue(Base64.decode(r.body.getString("ink_png"), Base64.NO_WRAP).size > 100)
            // Memory candidates come from the allowed fields only: the text, never strokes or the image.
            val c = LegacyVault.candidates(store.all(), VaultState())
            assertEquals(listOf("text"), c.map { it.source.field })
            assertFalse(c.single().text.contains("iVBOR"))
            // The export keeps the image byte for byte (not scrubbed as if it were text).
            val out = java.io.ByteArrayOutputStream()
            io.github.daddymean.agentickeyboard.twin.export.TwinExporter(null, store).export(out, "correct horse battery".toCharArray(), 1, iterations = 100_000)
            val zip = java.io.ByteArrayOutputStream()
            io.github.daddymean.agentickeyboard.twin.export.PassphraseArchive.Reader(out.toByteArray().inputStream(), "correct horse battery".toCharArray()).use { it.copyTo(zip) }
            var exported: JSONObject? = null
            java.util.zip.ZipInputStream(zip.toByteArray().inputStream()).use { z ->
                while (true) { val e = z.nextEntry ?: break; if (e.name == "journal/records.jsonl") exported = JSONObject(String(z.readBytes()).trim()) }
            }
            assertEquals(r.body.getString("ink_png"), exported!!.getJSONObject("body").getString("ink_png"))
        } finally { store.close(); dir.deleteRecursively() }
    }

    @Test
    fun recognitionIsOffUntilKeithApproves() {
        fun ev(b: JSONObject, id: Long) = JournalRecord(id, id, JournalKinds.EVENT, b)
        assertFalse(InkModelGate.approved(emptyList()))
        val yes = listOf(ev(InkModelGate.approval(true), 1))
        assertTrue(InkModelGate.approved(yes))
        assertFalse(InkModelGate.approved(yes + ev(InkModelGate.approval(false), 2)))
        assertThrows(IllegalStateException::class.java) { InkRecognizer(false).download { } }
        assertThrows(IllegalStateException::class.java) { InkRecognizer(false).recognize(sample()) { _, _ -> } }
        for (w in listOf("downloaded once from Google", "Wi-Fi", "never sent", "diagnostics")) assertTrue(w, InkModelGate.DISCLOSURE.contains(w))
    }

    @Test
    fun onlyTheRecognizerFileUsesMlKitAndItChecksTheGate() {
        val root = listOf(File("."), File("..")).first { File(it, "app/src/main").isDirectory }
        val main = File(root, "app/src/main/java/io/github/daddymean/agentickeyboard")
        val users = main.walkTopDown().filter { it.isFile && it.readText().contains("com.google.mlkit.vision.digitalink") }.map { it.name }.toList()
        assertEquals(listOf("InkRecognizer.kt"), users)
        val src = File(main, "twin/journal/InkRecognizer.kt").readText()
        assertTrue(src.contains("check(approved)") && src.contains("requireWifi()"))
    }
}
