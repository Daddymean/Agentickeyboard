package io.github.daddymean.agentickeyboard.twin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TwinStoragePolicyTest {
    private val mb = 1024L * 1024
    private lateinit var context: Context
    private lateinit var dir: File
    private lateinit var store: TwinStore
    private lateinit var prefs: TwinPreferences
    private val notices = ArrayList<String>()
    private var free = 10_000 * 1024L * 1024

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir = File(context.noBackupFilesDir, "twin-policy-test").apply { deleteRecursively() }
        store = TwinStore(context, File(dir, "twin_store.db"), SoftwareTwinCipher())
        prefs = TwinPreferences(context)
        context.getSharedPreferences(TwinPreferences.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun writer(step: Long = TwinStoragePolicy.WARNING_STEP_BYTES) =
        TwinWriter(store, prefs, freeBytes = { free }, notify = { notices += it }, warningStep = step)

    @Test
    fun noticeAt64MbAndEveryFurther64MbOnlyOnce() {
        assertNull(TwinStoragePolicy.tierToAnnounce(63 * mb, 0))
        assertEquals(1, TwinStoragePolicy.tierToAnnounce(64 * mb, 0))
        assertNull(TwinStoragePolicy.tierToAnnounce(100 * mb, 1))
        assertEquals(2, TwinStoragePolicy.tierToAnnounce(128 * mb, 1))
        assertEquals(3, TwinStoragePolicy.tierToAnnounce(200 * mb, 1))
        assertTrue(TwinStoragePolicy.sizeNotice(2).contains("128 MB"))
    }

    @Test
    fun writerAnnouncesEachStepOnceAndNeverDeletes() {
        val step = 64 * 1024L
        val w = writer(step)
        var written = 0
        while (store.fileBytes() < 3 * step + step / 2) {
            assertTrue(w.write("message ${written++} " + "y".repeat(300), "pkg", written.toLong()))
        }
        assertEquals(3, notices.size)
        assertEquals(3, prefs.announcedSizeTier)
        assertEquals(written, store.count())
    }

    @Test
    fun lowDeviceStoragePausesAndWarnsOnceWithoutDeleting() {
        val w = writer()
        assertTrue(w.write("before", "pkg", 1))
        free = TwinStoragePolicy.MIN_FREE_BYTES - 1
        assertFalse(w.write("while low 1", "pkg", 2))
        assertFalse(w.write("while low 2", "pkg", 3))
        assertEquals(listOf(TwinStoragePolicy.LOW_STORAGE_NOTICE), notices)
        assertTrue(prefs.isLowStoragePaused)
        assertEquals(listOf("before"), store.recent(10).map { it.text })

        free = TwinStoragePolicy.MIN_FREE_BYTES
        assertTrue(w.write("after", "pkg", 4))
        assertFalse(prefs.isLowStoragePaused)
        assertEquals(listOf("after", "before"), store.recent(10).map { it.text })
    }
}
