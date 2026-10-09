package io.github.daddymean.agentickeyboard.util

import dev.context.core.model.Sensitivity
import dev.context.core.model.Snapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure helpers for the keyboard's personal-context integration: the "Save note"
 * action and the read-only context chip. Kept free of Android types so they run
 * as plain JVM unit tests.
 */
object ContextNotes {

    /** Longest chip title before it is shortened with an ellipsis. */
    const val MAX_CHIP_TITLE_CHARS = 28

    /**
     * Sensitivity for a keyboard note: PERSONAL (may sync to the user's cloud)
     * only when the user opted in, otherwise PRIVATE (stays on this phone).
     */
    fun noteSensitivity(syncToCloud: Boolean): Int =
        if (syncToCloud) Sensitivity.PERSONAL else Sensitivity.PRIVATE

    /** The note to save, trimmed; null when there is nothing worth saving. */
    fun noteTextOrNull(text: String): String? = text.trim().takeIf { it.isNotEmpty() }

    /**
     * Chip text for [snapshot]: "Next: <title> <HH:mm>" for an upcoming event
     * (with a weekday when it is not today), else the active episode's title,
     * else null (chip hidden). An event that has already started is ignored.
     */
    fun chipText(
        snapshot: Snapshot?,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault()
    ): String? {
        if (snapshot == null) return null
        val next = snapshot.nextEvent
        if (next != null && next.title.isNotBlank() && next.startMs >= nowMs) {
            val start = Instant.ofEpochMilli(next.startMs).atZone(zone)
            val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
            val pattern = if (start.toLocalDate() == today) "HH:mm" else "EEE HH:mm"
            val time = DateTimeFormatter.ofPattern(pattern, locale).format(start)
            return "Next: ${shorten(next.title)} $time"
        }
        val episode = snapshot.activeEpisode?.title?.takeIf { it.isNotBlank() } ?: return null
        return shorten(episode)
    }

    private fun shorten(title: String): String {
        val clean = title.trim().replace(WHITESPACE, " ")
        return if (clean.length <= MAX_CHIP_TITLE_CHARS) clean
        else clean.take(MAX_CHIP_TITLE_CHARS - 1).trimEnd() + "…"
    }

    private val WHITESPACE = "\\s+".toRegex()
}
