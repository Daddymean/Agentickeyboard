package io.github.daddymean.agentickeyboard.util

import android.content.ClipboardManager

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

    /** Reads the flag from the current primary clip's description. */
    fun isPrimaryClipFlaggedSensitive(manager: ClipboardManager): Boolean =
        isFlaggedSensitive { key ->
            manager.primaryClipDescription?.extras?.getBoolean(key, false) == true
        }
}
