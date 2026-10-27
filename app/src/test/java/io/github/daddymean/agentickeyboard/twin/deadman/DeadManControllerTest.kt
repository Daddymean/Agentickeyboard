package io.github.daddymean.agentickeyboard.twin.deadman

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.daddymean.agentickeyboard.twin.SoftwareTwinCipher
import io.github.daddymean.agentickeyboard.twin.export.PassphraseArchive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/**
 * KEYBOARD-039: the screen's change sequence (Codex's #184 review). Every change applies to
 * the latest saved state, so an exported archive stays openable through later check-ins
 * and holder changes, and a stale change is refused instead of rolling state back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeadManControllerTest {
    private lateinit var dir: File
    private lateinit var store: DeadManStore
    private lateinit var c: DeadManController
    private val today = LocalDate.of(2026, 10, 21)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        dir = File(context.noBackupFilesDir, "deadman-controller-test").also { it.deleteRecursively(); it.mkdirs() }
        store = DeadManStore(File(dir, "deadman.sealed"), SoftwareTwinCipher())
        c = DeadManController(store)
        assertNull(c.load())
    }

    @After
    fun tearDown() { dir.deleteRecursively() }

    private fun fails(block: () -> Unit) = assertTrue(runCatching(block).isFailure)
    private fun checks(r: Rekey) = r.cards.map { it.second.encode().substringAfterLast('-') }
    private fun confirm(r: Rekey) = c.confirmCards(r, checks(r))

    /** Writes an archive the way the screen does: prepare, encrypt under the new key, commit. */
    private fun export(payload: String): ByteArray {
        val next = c.prepareArchive()
        val bytes = ByteArrayOutputStream().also { out ->
            PassphraseArchive.Writer(out, PassphraseArchive.dataKeyParams(next.dataKey, next.archiveId)).use { it.write(payload.toByteArray()) }
        }.toByteArray()
        c.commitArchive(next)
        return bytes
    }

    private fun open(bytes: ByteArray, dek: ByteArray) =
        String(PassphraseArchive.Reader(bytes.inputStream(), dek).readBytes())

    private fun dekFromCards(r: Rekey) = DeadManSwitch.unwrap(c.state!!.keyFile, r.cards.take(2).map { it.second })

    @Test
    fun exportThenCheckInThenReissueAndReplaceStillOpensThatArchive() {
        confirm(c.create(today))
        val archive = export("archive A")
        c.checkIn(today.plusDays(1))
        val reissued = c.reissue(today.plusDays(1))
        confirm(reissued)
        assertEquals("archive A", open(archive, dekFromCards(reissued)))
        val replaced = c.replaceHolder(2, "Kid 1", LocalDate.of(2000, 1, 1), today.plusDays(2))
        confirm(replaced)
        assertEquals("archive A", open(archive, dekFromCards(replaced)))
        // The check-in survived both holder changes, and a reload sees the same state.
        assertEquals(today.plusDays(1), c.state!!.lastCheckIn)
        assertEquals(c.state, DeadManController(store).load())
    }

    @Test
    fun cardsMadeBeforeAnArchiveWasSavedAreRefused() {
        confirm(c.create(today))
        val stale = c.reissue(today)
        val archive = export("archive B")
        val before = c.state
        fails { confirm(stale) }
        assertEquals(before, c.state)
        assertEquals(before, DeadManController(store).load())
        // Starting again from the current state works and opens the new archive.
        val fresh = c.reissue(today)
        confirm(fresh)
        assertEquals("archive B", open(archive, dekFromCards(fresh)))
    }

    @Test
    fun cardsMadeBeforeACheckInAreRefusedSoTheCheckInIsntUndone() {
        confirm(c.create(today))
        val stale = c.reissue(today)
        c.checkIn(today.plusDays(5))
        fails { confirm(stale) }
        assertEquals(today.plusDays(5), c.state!!.lastCheckIn)
    }

    @Test
    fun anArchivePreparedBeforeAHolderChangeIsNotCommitted() {
        confirm(c.create(today))
        val next = c.prepareArchive()
        confirm(c.reissue(today))
        val after = c.state
        fails { c.commitArchive(next) }
        assertEquals(after, c.state)
    }

    @Test
    fun aCheckInDuringAnExportIsKeptWhenTheArchiveCommits() {
        confirm(c.create(today))
        val next = c.prepareArchive()
        c.checkIn(today.plusDays(3))
        c.commitArchive(next)
        val s = c.state!!
        assertEquals(today.plusDays(3), s.lastCheckIn)
        assertEquals(today.plusDays(3).toString(), s.keyFile.getJSONObject("policy").getString("last_check_in"))
        assertArrayEquals(next.archiveId, s.archiveId)
        assertEquals(hex(next.archiveId), s.keyFile.getString("archive_id"))
    }

    @Test
    fun wrongChecksAndSecondSetUpAreRefused() {
        val r = c.create(today)
        fails { c.confirmCards(r, listOf("00000000", "00000000", "00000000")) }
        assertNull(c.state)
        confirm(r)
        fails { c.create(today) }
    }
}
