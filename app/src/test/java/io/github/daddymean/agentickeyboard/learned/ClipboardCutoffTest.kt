package io.github.daddymean.agentickeyboard.learned

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.db.AppDatabase
import io.github.daddymean.agentickeyboard.db.KeyboardRepository
import io.github.daddymean.agentickeyboard.util.ClipSnapshot
import io.github.daddymean.agentickeyboard.util.ClipboardCapture
import io.github.daddymean.agentickeyboard.util.ClipboardCaptureOutcome
import io.github.daddymean.agentickeyboard.util.ClipboardCaptureDecision
import io.github.daddymean.agentickeyboard.util.ClipboardCutoff
import io.github.daddymean.agentickeyboard.util.ClipboardHistoryPolicy
import io.github.daddymean.agentickeyboard.util.ClipboardSensitivity
import io.github.daddymean.agentickeyboard.util.KeyboardSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #231 second review, blocker 1: a clip deleted from clipboard history must not be written back
 * by the keyboard's window-shown capture while it is still on the system clipboard. Every test
 * runs the same capture path the keyboard and the clipboard history screen use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardCutoffTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var repo: KeyboardRepository
    private lateinit var settings: KeyboardSettings
    private lateinit var data: KeyboardLearnedData

    private val secret = "meet at the north entrance at nine"

    @Before
    fun setUp() {
        context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .addCallback(AppDatabase.SECURE_DELETE).allowMainThreadQueries().build()
        repo = KeyboardRepository(db)
        settings = KeyboardSettings(context)
        data = KeyboardLearnedData(db, settings, context.getSharedPreferences("clip_cutoff_test", Context.MODE_PRIVATE))
    }

    @After
    fun tearDown() = db.close()

    private fun clips() = runBlocking { repo.allClipboardHistory.first().map { it.content } }

    /** The keyboard shows its window and re-reads the clip copied at [copiedAt]. */
    private fun windowShown(text: String = secret, copiedAt: Long) =
        runBlocking { ClipboardCapture.capture(ClipSnapshot(text, false, copiedAt), settings, repo) }

    /** The clip was copied a minute ago and captured when the keyboard last opened. */
    private fun capturedEarlier(): Long {
        val copiedAt = System.currentTimeMillis() - 60_000
        assertEquals(ClipboardCaptureOutcome.Saved, windowShown(copiedAt = copiedAt))
        assertEquals(listOf(secret), clips())
        return copiedAt
    }

    @Test
    fun deletingTheCurrentClipKeepsItDeleted() = runBlocking {
        val copiedAt = capturedEarlier()
        val entry = data.entries(KeyboardStore.CLIPBOARD, 10).single()
        assertEquals(1, data.delete(entry))

        assertEquals(ClipboardCaptureOutcome.SkippedCleared, windowShown(copiedAt = copiedAt))
        assertEquals(emptyList<String>(), clips())
    }

    @Test
    fun clearingClipboardHistoryKeepsItCleared() = runBlocking {
        val copiedAt = capturedEarlier()
        assertEquals(1, data.clear(KeyboardStore.CLIPBOARD))

        assertEquals(ClipboardCaptureOutcome.SkippedCleared, windowShown(copiedAt = copiedAt))
        assertEquals(emptyList<String>(), clips())
    }

    @Test
    fun clearEverythingKeepsClipsCleared() = runBlocking {
        val copiedAt = capturedEarlier()
        assertEquals(1, data.clearAll()[KeyboardStore.CLIPBOARD])

        assertEquals(ClipboardCaptureOutcome.SkippedCleared, windowShown(copiedAt = copiedAt))
        assertEquals(emptyList<String>(), clips())
    }

    @Test
    fun theOlderClipboardHistoryScreenUsesTheSameCutoff() = runBlocking {
        val copiedAt = capturedEarlier()
        ClipboardCutoff.deleteClip(settings, repo, repo.allClipboardHistory.first().single().id)
        assertEquals(ClipboardCaptureOutcome.SkippedCleared, windowShown(copiedAt = copiedAt))
        assertEquals(emptyList<String>(), clips())

        // A newer copy is saved, then "Clear all" there keeps it cleared too.
        val newer = settings.clipboardClearedAt + 1
        assertEquals(ClipboardCaptureOutcome.Saved, windowShown(copiedAt = newer))
        ClipboardCutoff.clearHistory(settings, repo)
        assertEquals(ClipboardCaptureOutcome.SkippedCleared, windowShown(copiedAt = newer))
        assertEquals(emptyList<String>(), clips())
    }

    @Test
    fun aNewerCopyIsStillCaptured() = runBlocking {
        capturedEarlier()
        data.clear(KeyboardStore.CLIPBOARD)
        val newer = settings.clipboardClearedAt + 1
        assertEquals(ClipboardCaptureOutcome.Saved, windowShown("a new clip after the clear", newer))
        // Copying the deleted text again is a new copy, so it is saved again.
        assertEquals(ClipboardCaptureOutcome.Saved, windowShown(copiedAt = newer + 1))
        assertEquals(setOf("a new clip after the clear", secret), clips().toSet())
    }

    @Test
    fun aClipCopiedInTheSameMillisecondAsTheClearIsSkipped() = runBlocking {
        ClipboardCutoff.markCleared(settings, now = 5_000)
        assertEquals(ClipboardCaptureOutcome.SkippedCleared, windowShown(copiedAt = 5_000))
        assertEquals(ClipboardCaptureOutcome.Saved, windowShown(copiedAt = 5_001))
    }

    @Test
    fun withoutACopyTimeTheClipIsSkippedUntilTheClipboardChanges() = runBlocking {
        capturedEarlier()
        data.clear(KeyboardStore.CLIPBOARD)
        // No ClipDescription timestamp: can't tell old from new, so fail closed.
        assertEquals(ClipboardCaptureOutcome.SkippedCleared, windowShown(copiedAt = 0))
        assertEquals(ClipboardCaptureOutcome.SkippedCleared, windowShown(copiedAt = 0))
        assertEquals(emptyList<String>(), clips())

        // The keyboard's primary-clip listener saw a new copy.
        ClipboardCutoff.onPrimaryClipChanged(settings)
        assertEquals(ClipboardCaptureOutcome.Saved, windowShown("copied after the clear", 0))
    }

    @Test
    fun aNewerTimestampedCopyAlsoEndsTheNoTimestampSkip() = runBlocking {
        ClipboardCutoff.markCleared(settings, now = 5_000)
        assertEquals(ClipboardCaptureOutcome.SkippedCleared, windowShown(copiedAt = 0))
        assertEquals(ClipboardCaptureOutcome.Saved, windowShown("newer", 6_000))
        assertEquals(ClipboardCaptureOutcome.Saved, windowShown("untimestamped later", 0))
    }

    @Test
    fun beforeAnyClearNothingIsSkipped() = runBlocking {
        assertEquals(ClipboardCaptureOutcome.Saved, windowShown(copiedAt = 0))
        assertEquals(ClipboardCaptureOutcome.Saved, windowShown("another", 1_000))
    }

    @Test
    fun theCutoffStoresNoClipTextOrHash() = runBlocking {
        capturedEarlier()
        val hash = (ClipboardHistoryPolicy.evaluate(secret) as ClipboardCaptureDecision.Accept).contentHash
        data.delete(data.entries(KeyboardStore.CLIPBOARD, 10).single())
        data.clear(KeyboardStore.CLIPBOARD)
        data.clearAll()
        val stored = context.getSharedPreferences("keyboard_settings", Context.MODE_PRIVATE).all.values.map { it.toString() }
        assertTrue(settings.clipboardClearedAt > 0)
        assertFalse(stored.any { it.contains(secret) || it.contains(hash) || it.contains("north") })
    }

    @Test
    fun theSystemClipboardPathReadsTheCopyTime() {
        // End to end through the real ClipboardManager read used on window shown.
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("test", secret)
        val copiedAt = System.currentTimeMillis() - 60_000
        ClipDescription::class.java.getMethod("setTimestamp", Long::class.javaPrimitiveType).invoke(clip.description, copiedAt)
        assertEquals(copiedAt, ClipboardSensitivity.snapshotOf(clip).copiedAt)

        manager.setPrimaryClip(clip)
        val snapshot = ClipboardSensitivity.readPrimaryClip(manager)!!
        runBlocking {
            assertEquals(ClipboardCaptureOutcome.Saved, ClipboardCapture.capture(snapshot, settings, repo))
            data.clear(KeyboardStore.CLIPBOARD)
            assertEquals(ClipboardCaptureOutcome.SkippedCleared, ClipboardCapture.capture(ClipboardSensitivity.readPrimaryClip(manager), settings, repo))
        }
        assertEquals(emptyList<String>(), clips())
    }
}
