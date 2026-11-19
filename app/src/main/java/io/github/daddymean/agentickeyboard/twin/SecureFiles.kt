package io.github.daddymean.agentickeyboard.twin

import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/**
 * Replaces and removes sealed twin files without leaving the previous ciphertext behind as a
 * plain unlinked file. Before a file is deleted its bytes are overwritten with random data and
 * synced. On flash storage the controller may still keep old blocks, so this is best effort;
 * the real guarantee stays the Keystore key (delete-all destroys it).
 *
 * [replace] keeps saves atomic: the current file moves to `.old`, the new one takes its name,
 * then `.old` is wiped. [recover] puts `.old` back if a crash left no current file.
 */
object SecureFiles {
    private val rng = SecureRandom()

    /** Overwrites [f] with random bytes, syncs, deletes it. True when it is gone. */
    fun wipe(f: File): Boolean {
        if (!f.exists()) return true
        runCatching {
            RandomAccessFile(f, "rw").use { raf ->
                val buf = ByteArray(64 * 1024)
                var left = raf.length()
                raf.seek(0)
                while (left > 0) {
                    val n = minOf(left, buf.size.toLong()).toInt()
                    rng.nextBytes(buf)
                    raf.write(buf, 0, n)
                    left -= n
                }
                raf.fd.sync()
            }
        }
        return f.delete() || !f.exists()
    }

    fun old(target: File) = File(target.path + ".old")
    fun tmp(target: File) = File(target.path + ".tmp")

    /** Moves [tmp] over [target] and wipes the previous version. */
    fun replace(tmp: File, target: File) {
        val old = old(target)
        wipe(old)
        if (target.exists()) check(target.renameTo(old)) { "could not save" }
        if (!tmp.renameTo(target)) {
            old.renameTo(target)
            error("could not save")
        }
        wipe(old)
    }

    /** Call before reading [target]: restores an interrupted [replace], or wipes a stale `.old`. */
    fun recover(target: File) {
        val old = old(target)
        if (!old.exists()) return
        if (!target.exists()) old.renameTo(target) else wipe(old)
    }

    /** Wipes [target] and its `.tmp` and `.old` companions. */
    fun wipeAll(target: File) {
        wipe(target); wipe(tmp(target)); wipe(old(target))
    }
}
