package io.github.daddymean.agentickeyboard.util

import android.content.ClipData
import android.content.ClipboardManager

/** The primary clip's text and sensitivity flag, read from one [ClipData]. */
data class ClipSnapshot(val text: String?, val flaggedSensitive: Boolean)

/**
 * Honors the "sensitive clip" flag a source app sets on its ClipDescription
 * extras (password managers, banking apps, OTP fields). Such clips are never
 * captured into history and never offered to the clipboard AI actions
 * (KEYBOARD-004).
 */
object ClipboardSensitivity {
    /**
     * `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+) has exactly this value, and
     * apps targeting older API levels are told to set the same literal key, so
     * one lookup covers both the platform constant and the legacy extra.
     */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    /**
     * True when the flag is set. If the extras cannot be read at all, the clip is
     * treated as sensitive: like [ClipboardHistoryPolicy], prefer a false
     * rejection over retaining something the source app wanted protected.
     */
    fun isFlaggedSensitive(readBooleanExtra: (String) -> Boolean): Boolean =
        runCatching { readBooleanExtra(EXTRA_IS_SENSITIVE) }.getOrDefault(true)

    /**
     * Text and flag from the same [clip], so a clipboard change between two
     * separate reads can never pair one clip's text with another clip's flag.
     */
    fun snapshotOf(clip: ClipData): ClipSnapshot = ClipSnapshot(
        text = clip.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString(),
        flaggedSensitive = isFlaggedSensitive { key ->
            clip.description?.extras?.getBoolean(key, false) == true
        }
    )

    /** Reads the primary clip exactly once; null when there is no clip. */
    fun readPrimaryClip(manager: ClipboardManager): ClipSnapshot? =
        manager.primaryClip?.let(::snapshotOf)
}
