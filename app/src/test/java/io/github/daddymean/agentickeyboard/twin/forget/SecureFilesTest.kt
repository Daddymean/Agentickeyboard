package io.github.daddymean.agentickeyboard.twin.forget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.twin.SecureFiles
import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore
import io.github.daddymean.agentickeyboard.twin.memory.EncryptedVectorIndex
import io.github.daddymean.agentickeyboard.twin.memory.LegacyVault
import io.github.daddymean.agentickeyboard.twin.memory.MemoryStore
import io.github.daddymean.agentickeyboard.twin.memory.VaultState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** Review #230 item 5: no previous ciphertext left as a plain unlinked file after a save or delete. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SecureFilesTest {
    private lateinit var dir: File
    private val cipher = SoftwareTwinCipher()

    @Before fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir, "secure-files-test").also { it.deleteRecursively(); it.mkdirs() }
    }

    @After fun tearDown() { dir.deleteRecursively() }

    /** A second name for the same inode shows whether the bytes were overwritten before unlink. */
    private fun shadow(f: File): File = File(dir, "shadow-" + f.name).also { Files.createLink(it.toPath(), f.toPath()) }

    @Test
    fun wipeOverwritesBeforeDeleting() {
        val f = File(dir, "x.sealed").apply { writeBytes(ByteArray(100_000) { 42 }) }
        val s = shadow(f)
        assertTrue(SecureFiles.wipe(f))
        assertFalse(f.exists())
        val left = s.readBytes()
        assertEquals(100_000, left.size)
        assertTrue(left.count { it == 42.toByte() } < 2_000)
    }

    @Test
    fun savingTheVaultWipesThePreviousVersion() {
        val f = File(dir, "memory.sealed")
        val store = MemoryStore(f, cipher)
        store.save(LegacyVault.setCore(VaultState(), LegacyVault.WHO_I_AM, "first version", 1))
        val before = f.readBytes()
        val s = shadow(f)
        store.save(VaultState())
        assertFalse(s.readBytes().contentEquals(before))
        assertEquals(listOf("memory.sealed", "shadow-memory.sealed"), dir.list()!!.sorted())
        store.delete()
        assertFalse(f.exists())
    }

    @Test
    fun anInterruptedReplaceIsRecovered() {
        val f = File(dir, "memory-vectors.sealed")
        val index = EncryptedVectorIndex(f, cipher)
        index.save(EncryptedVectorIndex.Payload("m", 2, listOf(EncryptedVectorIndex.Entry("a", floatArrayOf(1f, 2f)))))
        // Crash after the current file moved to .old, before .tmp took its name.
        assertTrue(f.renameTo(SecureFiles.old(f)))
        assertEquals(listOf("a"), index.load().entries.map { it.memoryId })
        assertFalse(SecureFiles.old(f).exists())
        assertEquals(1, index.prune(emptySet()))
        index.delete()
        assertTrue(dir.list()!!.isEmpty())
    }

    @Test
    fun deletedRecordingsAreOverwritten() {
        val journal = JournalStore(ApplicationProvider.getApplicationContext(), dir, cipher)
        val (id, w) = journal.newRecording()
        w.use { it.write(ByteArray(50_000) { 9 }) }
        val audio = dir.walkTopDown().single { it.isFile && it.name.contains(id) }
        val before = audio.readBytes()
        val s = shadow(audio)
        journal.deleteAudio(id)
        assertFalse(audio.exists())
        assertFalse(s.readBytes().contentEquals(before))
        journal.close()
    }
}
