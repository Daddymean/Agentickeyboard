package io.github.daddymean.agentickeyboard.db

import java.io.File
import java.util.Collections

/**
 * Persistent counters for keyboard-database open and migration failures, kept next to the
 * wrapped key in `noBackupFilesDir`. Used for backoff (M4) and the one-tap reset offer (H2).
 * An unavailable open is counted at most once per process, so a brief Keystore hiccup after
 * boot cannot burn through the reset threshold in a single launch.
 */
class KeyboardDbHealth(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val backoffMs: Long = DEFAULT_BACKOFF_MS,
    val resetAfter: Int = RESET_AFTER,
) {
    var unavailableCount: Int = 0
        private set
    var migrationFailures: Int = 0
        private set
    var lastReason: String? = null
        private set
    var lastAt: Long = 0L
        private set

    private val persistEpoch = Companion.persistEpoch

    init {
        persistedFiles.add(file)
        load()
    }

    fun offerReset(): Boolean = unavailableCount >= resetAfter

    fun shouldRetryMigration(): Boolean {
        if (migrationFailures <= 0) return true
        val wait = backoffFor(migrationFailures)
        return now() - lastAt >= wait
    }

    fun recordUnavailable(): Int {
        if (unavailableCountedThisProcess) return unavailableCount
        unavailableCountedThisProcess = true
        unavailableCount += 1
        lastReason = "key"
        lastAt = now()
        save()
        return unavailableCount
    }

    fun recordMigrationFailure(reason: String) {
        migrationFailures += 1
        lastReason = reason
        lastAt = now()
        save()
    }

    fun recordSuccess() {
        if (unavailableCount == 0 && migrationFailures == 0 && lastReason == null) return
        unavailableCount = 0
        migrationFailures = 0
        lastReason = null
        lastAt = 0L
        runCatching { file.delete() }
    }

    fun clear() = recordSuccess()

    private fun backoffFor(failures: Int): Long {
        var wait = backoffMs
        repeat((failures - 1).coerceAtLeast(0)) {
            wait = (wait * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
        return wait
    }

    private fun load() {
        runCatching {
            if (!file.isFile) return
            val map = file.readLines().mapNotNull { line ->
                line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }.toMap()
            unavailableCount = map["unavailable"]?.toIntOrNull() ?: 0
            migrationFailures = map["migrations"]?.toIntOrNull() ?: 0
            lastReason = map["reason"]?.ifEmpty { null }
            lastAt = map["at"]?.toLongOrNull() ?: 0L
        }
    }

    private fun save() {
        if (persistEpoch != Companion.persistEpoch) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(
                "unavailable=$unavailableCount\nmigrations=$migrationFailures\n" +
                    "reason=${lastReason.orEmpty()}\nat=$lastAt\n"
            )
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    companion object {
        const val DEFAULT_BACKOFF_MS = 5 * 60 * 1000L
        const val MAX_BACKOFF_MS = 4 * 60 * 60 * 1000L
        const val RESET_AFTER = 3
        const val ASIDE_CAP = 3

        @Volatile
        private var unavailableCountedThisProcess = false
        @Volatile
        private var persistEpoch = 0
        private val persistedFiles: MutableSet<File> = Collections.synchronizedSet(mutableSetOf())

        /** For tests: the next [recordUnavailable] counts as a new app launch. */
        fun newLaunch() {
            unavailableCountedThisProcess = false
        }

        /**
         * Test-only: drop on-disk health counters and ignore [save] from earlier instances,
         * so a previous test's engine/key failure cannot force [plaintext:backoff] here.
         */
        fun resetPersistedForTests() {
            persistEpoch += 1
            unavailableCountedThisProcess = false
            val files = synchronized(persistedFiles) {
                persistedFiles.toList().also { persistedFiles.clear() }
            }
            files.forEach { f ->
                runCatching { f.delete() }
                runCatching { File(f.path + ".tmp").delete() }
            }
        }

        fun file(dir: File) = File(dir, "health")
    }
}
