package io.github.daddymean.agentickeyboard.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelManagerTest {
    private lateinit var context: Context
    private lateinit var root: File
    private val bytes = "embedding-weights-v1".toByteArray()
    private val pin = sha(bytes)
    private val spec get() = ModelSpec(
        id = "test-model",
        fileName = "model.bin",
        downloadUrl = "https://example.invalid/model.bin",
        sha256 = pin,
        sizeBytes = bytes.size.toLong(),
        license = "Apache-2.0"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.noBackupFilesDir, "models-test").apply { deleteRecursively(); mkdirs() }
        context.getSharedPreferences(ModelConsent.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun optInIsOffByDefaultAndDownloadWithoutOptInWritesNothing() {
        val mgr = manager { error("fetcher must not run") }
        assertFalse(mgr.isOptedIn(spec.id))
        assertEquals(DownloadResult.NotOptedIn, mgr.download(spec))
        assertFalse(mgr.fileFor(spec).exists())
        assertEquals(0, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun hashMismatchDeletesTheFileAndDoesNotKeepAReadyModel() {
        val mgr = manager { dest -> dest.parentFile?.mkdirs(); dest.writeBytes(bytes) }
        mgr.setOptedIn(spec, true)
        val bad = spec.copy(sha256 = "00".repeat(32))
        val result = mgr.download(bad)
        assertTrue(result is DownloadResult.HashMismatch)
        assertEquals(pin, (result as DownloadResult.HashMismatch).actualSha256)
        assertFalse(mgr.fileFor(bad).exists())
        assertFalse(File(mgr.fileFor(bad).path + ".tmp").exists())
        assertFalse(mgr.isReady(bad))
    }

    @Test
    fun verifiedDownloadMatchesThePin() {
        val mgr = manager { dest -> dest.parentFile?.mkdirs(); dest.writeBytes(bytes) }
        mgr.setOptedIn(spec, true)
        val result = mgr.download(spec)
        assertTrue(result is DownloadResult.Ready)
        assertEquals(pin, ModelManager.sha256((result as DownloadResult.Ready).file))
        assertTrue(mgr.isReady(spec))
        assertEquals(mgr.fileFor(spec).canonicalPath, mgr.verifiedFile(spec)?.canonicalPath)
    }

    @Test
    fun verifiedFileDeletesATamperedCopy() {
        val mgr = manager { dest -> dest.parentFile?.mkdirs(); dest.writeBytes(bytes) }
        mgr.setOptedIn(spec, true)
        mgr.download(spec)
        mgr.fileFor(spec).writeText("tampered")
        assertEquals(null, mgr.verifiedFile(spec))
        assertFalse(mgr.fileFor(spec).exists())
    }

    @Test
    fun revokingOptInDeletesTheModel() {
        val mgr = manager { dest -> dest.parentFile?.mkdirs(); dest.writeBytes(bytes) }
        mgr.setOptedIn(spec, true)
        mgr.download(spec)
        assertTrue(mgr.fileFor(spec).exists())
        mgr.setOptedIn(spec, false)
        assertFalse(mgr.isOptedIn(spec.id))
        assertFalse(mgr.fileFor(spec).exists())
    }

    private fun manager(write: (File) -> Unit) =
        ModelManager(root, ModelConsent(context), ModelBytesFetcher { _, dest -> write(dest) })

    private fun sha(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
}
