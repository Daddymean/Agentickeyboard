package io.github.daddymean.agentickeyboard.models

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
import java.io.IOException
import java.security.MessageDigest

/** Resume, retry, verify and progress behaviour of [ModelManager] with a scripted fetcher (no network). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelResumeTest {
    private lateinit var context: Context
    private lateinit var root: File
    private val data = ByteArray(10_000) { (it * 31 + 7).toByte() }
    private val pin = sha(data)
    private val spec = ModelSpec(
        id = "resume-model",
        fileName = "model.bin",
        downloadUrl = "https://example.invalid/model.bin",
        sha256 = pin,
        sizeBytes = data.size.toLong(),
        license = "Apache-2.0"
    )
    private val sleeps = mutableListOf<Long>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        root = File(context.noBackupFilesDir, "models-resume-test").apply { deleteRecursively(); mkdirs() }
        context.getSharedPreferences(ModelConsent.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        sleeps.clear()
        Thread.interrupted() // a failed earlier test must not leak an interrupt
    }

    /** A fetcher that appends `data` from dest.length() to `limit` bytes, then optionally throws. */
    private inner class ScriptedFetcher(
        private val steps: MutableList<(File) -> Unit>
    ) : ResumableModelFetcher {
        val startOffsets = mutableListOf<Long>()
        override fun fetchResumable(url: String, dest: File, onProgress: (Long) -> Unit) {
            startOffsets += if (dest.isFile) dest.length() else 0L
            if (steps.isEmpty()) throw IOException("no more scripted steps")
            steps.removeAt(0)(dest)
            onProgress(dest.length())
        }
    }

    private fun appendUpTo(dest: File, end: Int) {
        val have = if (dest.isFile) dest.length().toInt() else 0
        dest.parentFile?.mkdirs()
        dest.appendBytes(data.copyOfRange(have, end))
    }

    private fun manager(
        fetcher: ModelBytesFetcher,
        free: Long = Long.MAX_VALUE,
        delays: List<Long> = listOf(2_000L, 5_000L, 15_000L, 30_000L)
    ) = ModelManager(root, ModelConsent(context), fetcher, { free }, { sleeps += it }, delays).also {
        it.setOptedIn(spec, true)
    }

    @Test
    fun aDroppedConnectionResumesFromTheBytesAlreadyOnDisk() {
        val fetcher = ScriptedFetcher(mutableListOf(
            { d -> appendUpTo(d, 4_000); throw IOException("connection reset") },
            { d -> appendUpTo(d, data.size) }
        ))
        val progress = mutableListOf<Pair<Long, Long>>()
        val result = manager(fetcher).download(spec) { b, t -> progress += b to t }
        assertTrue(result is DownloadResult.Ready)
        assertEquals(listOf(0L, 4_000L), fetcher.startOffsets)
        assertEquals(listOf(2_000L), sleeps)
        assertEquals(pin, ModelManager.sha256(root.walkTopDown().first { it.name == "model.bin" }))
        assertEquals(data.size.toLong() to data.size.toLong(), progress.last())
        assertTrue("progress never goes backwards", progress.map { it.first }.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("only the final file is left", listOf("model.bin"), root.walkTopDown().filter { it.isFile }.map { it.name }.toList())
    }

    @Test
    fun exhaustedRetriesKeepThePartialSoALaterTapResumesIt() {
        val first = ScriptedFetcher(mutableListOf({ d -> appendUpTo(d, 6_000); throw IOException("offline") }))
        val m1 = manager(first)
        val failed = m1.download(spec)
        assertTrue(failed is DownloadResult.Failed)
        assertEquals(6_000L, m1.partialBytes(spec))
        // Four back-offs for four consecutive failures that made no progress after the first one.
        assertEquals(listOf(2_000L, 5_000L, 15_000L, 30_000L), sleeps)
        assertFalse(m1.fileFor(spec).exists())

        val second = ScriptedFetcher(mutableListOf({ d -> appendUpTo(d, data.size) }))
        val result = manager(second).download(spec)
        assertTrue(result is DownloadResult.Ready)
        assertEquals(listOf(6_000L), second.startOffsets)
    }

    @Test
    fun aPartialFromADifferentPinIsNeverContinued() {
        val old = ScriptedFetcher(mutableListOf({ d -> appendUpTo(d, 3_000); throw IOException("offline") }))
        manager(old, delays = emptyList()).download(spec)
        val newer = spec.copy(downloadUrl = "https://example.invalid/model-v2.bin")
        val fetcher = ScriptedFetcher(mutableListOf({ d -> appendUpTo(d, data.size) }))
        val m = ModelManager(root, ModelConsent(context), fetcher, { Long.MAX_VALUE }, { }, emptyList())
        m.setOptedIn(newer, true)
        assertEquals(0L, m.partialBytes(newer))
        assertTrue(m.download(newer) is DownloadResult.Ready)
        assertEquals(listOf(0L), fetcher.startOffsets)
    }

    @Test
    fun aBadResumedFileIsRetriedOnceFromByteZeroInsteadOfFailing() {
        val fetcher = ScriptedFetcher(mutableListOf(
            { d -> appendUpTo(d, 4_000); throw IOException("reset") },
            { d -> d.appendBytes(ByteArray(data.size - 4_000) { 9 }) }, // wrong tail
            { d -> appendUpTo(d, data.size) }
        ))
        val result = manager(fetcher).download(spec)
        assertTrue(result is DownloadResult.Ready)
        assertEquals(listOf(0L, 4_000L, 0L), fetcher.startOffsets)
    }

    @Test
    fun aBadFreshFileIsAHashMismatchAndLeavesNothingBehind() {
        val fetcher = ScriptedFetcher(mutableListOf({ d -> d.parentFile?.mkdirs(); d.writeBytes(ByteArray(data.size) { 1 }) }))
        val m = manager(fetcher)
        val result = m.download(spec)
        assertTrue(result is DownloadResult.HashMismatch)
        assertEquals(1, fetcher.startOffsets.size)
        assertEquals(0, root.walkTopDown().count { it.isFile })
        assertNull(m.verifiedFile(spec))
    }

    @Test
    fun aNotFoundIsNotRetriedButATemporaryServerErrorIs() {
        val gone = ScriptedFetcher(mutableListOf({ _ -> throw ModelHttpException(404) }))
        assertTrue(manager(gone).download(spec) is DownloadResult.Failed)
        assertEquals(1, gone.startOffsets.size)
        assertTrue(sleeps.isEmpty())

        val flaky = ScriptedFetcher(mutableListOf(
            { _ -> throw ModelHttpException(503) },
            { d -> appendUpTo(d, data.size) }
        ))
        assertTrue(manager(flaky).download(spec) is DownloadResult.Ready)
        assertEquals(2, flaky.startOffsets.size)
        assertEquals(listOf(2_000L), sleeps)
        assertTrue(ModelHttpException(429).retryable && ModelHttpException(408).retryable)
        assertFalse(ModelHttpException(403).retryable)
    }

    @Test
    fun aServerThatRejectsTheRangeRestartsFromZero() {
        val fetcher = ScriptedFetcher(mutableListOf(
            { d -> appendUpTo(d, 2_000); throw IOException("reset") },
            { _ -> throw ModelResumeRejectedException("server rejected the range") },
            { d -> appendUpTo(d, data.size) }
        ))
        assertTrue(manager(fetcher).download(spec) is DownloadResult.Ready)
        assertEquals(listOf(0L, 2_000L, 0L), fetcher.startOffsets)
    }

    @Test
    fun anOversizedPartialIsDiscardedAndAnAlreadyCompleteOneIsJustVerified() {
        val dir = File(root, spec.id).apply { mkdirs() }
        File(dir, "model.bin.part").writeBytes(ByteArray(data.size + 5))
        File(dir, "model.bin.part.meta").writeText("${spec.downloadUrl}\n${spec.sha256}\n${spec.sizeBytes}")
        val fetcher = ScriptedFetcher(mutableListOf({ d -> appendUpTo(d, data.size) }))
        assertTrue(manager(fetcher).download(spec) is DownloadResult.Ready)
        assertEquals(listOf(0L), fetcher.startOffsets)

        // Complete partial from an interrupted verify step: no network at all.
        File(dir, "model.bin").delete()
        File(dir, "model.bin.part").writeBytes(data)
        File(dir, "model.bin.part.meta").writeText("${spec.downloadUrl}\n${spec.sha256}\n${spec.sizeBytes}")
        val never = ScriptedFetcher(mutableListOf())
        assertTrue(manager(never).download(spec) is DownloadResult.Ready)
        assertTrue(never.startOffsets.isEmpty())
    }

    @Test
    fun notEnoughFreeStorageFailsBeforeAnyBytesAreFetched() {
        val fetcher = ScriptedFetcher(mutableListOf())
        val result = manager(fetcher, free = 100).download(spec)
        assertTrue(result is DownloadResult.Failed)
        assertTrue((result as DownloadResult.Failed).message.startsWith("Not enough free storage"))
        assertTrue(fetcher.startOffsets.isEmpty())
    }

    @Test
    fun aCancelledDownloadKeepsItsPartialAndStopsAtOnce() {
        val fetcher = ScriptedFetcher(mutableListOf({ d -> appendUpTo(d, 5_000); Thread.currentThread().interrupt() }, { d -> appendUpTo(d, data.size) }))
        val m = manager(fetcher)
        val result = try { m.download(spec) } finally { Thread.interrupted() }
        // The scripted step finished its bytes, then the interrupt was seen before hashing a short file.
        assertTrue(result is DownloadResult.Failed)
        assertEquals(5_000L, m.partialBytes(spec))
    }

    @Test
    fun revokingOptInRemovesThePartialToo() {
        val fetcher = ScriptedFetcher(mutableListOf({ d -> appendUpTo(d, 1_000); throw IOException("x") }))
        val m = manager(fetcher, delays = emptyList())
        m.download(spec)
        assertEquals(1_000L, m.partialBytes(spec))
        m.setOptedIn(spec, false)
        assertEquals(0L, m.partialBytes(spec))
        assertEquals(0, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun aPlainFetcherStillWorksAndNeverLeavesAPartialOnFailure() {
        val ok = manager(ModelBytesFetcher { _, dest -> dest.parentFile?.mkdirs(); dest.writeBytes(data) })
        assertTrue(ok.download(spec) is DownloadResult.Ready)
        ok.delete(spec)
        val broken = manager(ModelBytesFetcher { _, dest -> dest.writeBytes(ByteArray(10)); throw IOException("boom") })
        assertTrue(broken.download(spec) is DownloadResult.Failed)
        assertEquals(0, root.walkTopDown().count { it.isFile })
        assertTrue("a plain fetcher is not retried", sleeps.isEmpty())
    }

    @Test
    fun theVerifiedResultIsRememberedButAChangedFileIsHashedAgain() {
        val m = manager(ScriptedFetcher(mutableListOf({ d -> appendUpTo(d, data.size) })))
        assertTrue(m.download(spec) is DownloadResult.Ready)
        val file = m.fileFor(spec)
        assertTrue(m.isReady(spec))
        // Same size, different bytes, newer modified time: must not be trusted.
        val modified = file.lastModified()
        file.writeBytes(ByteArray(data.size) { 5 })
        file.setLastModified(modified + 5_000)
        assertNull(m.verifiedFile(spec))
        assertFalse(file.exists())
    }

    private fun sha(b: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
