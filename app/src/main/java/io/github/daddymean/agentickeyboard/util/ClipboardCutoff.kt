package io.github.daddymean.agentickeyboard.util

import io.github.daddymean.agentickeyboard.db.KeyboardRepository

/**
 * Keeps deleted clipboard-history clips deleted (#231 second review, blocker 1).
 *
 * The keyboard re-reads the system clipboard every time it is shown, so a clip deleted from
 * history while it is still the primary clip would be written straight back. On every
 * clipboard-history delete or clear we store only a "cleared at" time; captures of a clip
 * copied at or before that time are skipped, so only a new copy is saved again. Nothing about
 * the deleted clips is stored: no text, no hash.
 *
 * A single-clip delete sets the cutoff too. Only the current primary clip can be re-captured,
 * and checking whether it is the deleted clip would mean comparing content; the cutoff costs
 * nothing but a skipped refresh of the current clip's "last seen" time until the next copy.
 *
 * Clips without a copy time (`ClipDescription.getTimestamp()` is 0, or unreadable) can't be
 * placed before or after the cutoff. After a clear those are skipped until the system clipboard
 * is seen to change, either by the keyboard's primary-clip listener or by a timestamped clip
 * newer than the cutoff. This fails closed: a missed change means a clip isn't saved, never
 * that a deleted one comes back.
 */
object ClipboardCutoff {

    /** Call before any clipboard-history delete or clear. Stores only a time and a flag. */
    fun markCleared(settings: KeyboardSettings, now: Long = System.currentTimeMillis()) {
        settings.clipboardClearedAt = maxOf(settings.clipboardClearedAt, now)
        settings.clipboardSkipUntimestamped = true
    }

    /** True when a capture of a clip copied at [copiedAt] (0 = unknown) must be skipped. */
    fun shouldSkip(settings: KeyboardSettings, copiedAt: Long): Boolean {
        val clearedAt = settings.clipboardClearedAt
        if (clearedAt <= 0L) return false
        if (copiedAt > 0L) {
            if (copiedAt <= clearedAt) return true
            // A copy made after the clear: the clipboard has changed since.
            if (settings.clipboardSkipUntimestamped) settings.clipboardSkipUntimestamped = false
            return false
        }
        return settings.clipboardSkipUntimestamped
    }

    /** The system clipboard changed: whatever is on it now was copied after any clear. */
    fun onPrimaryClipChanged(settings: KeyboardSettings) {
        if (settings.clipboardSkipUntimestamped) settings.clipboardSkipUntimestamped = false
    }

    /** Deletes one clip, cutoff first. Used by every clipboard-history screen. */
    suspend fun deleteClip(settings: KeyboardSettings, repository: KeyboardRepository, id: Int) {
        markCleared(settings)
        repository.deleteClipboardItem(id)
    }

    /** Clears all clipboard history, cutoff first. Used by every clipboard-history screen. */
    suspend fun clearHistory(settings: KeyboardSettings, repository: KeyboardRepository) {
        markCleared(settings)
        repository.clearClipboardHistory()
    }
}

/** What one capture attempt did. */
sealed interface ClipboardCaptureOutcome {
    data object NoText : ClipboardCaptureOutcome
    /** Copied at or before the last delete/clear: not saved again. */
    data object SkippedCleared : ClipboardCaptureOutcome
    data class Rejected(val reason: ClipboardCaptureDecision.Reject) : ClipboardCaptureOutcome
    data object Saved : ClipboardCaptureOutcome
}

/**
 * The one capture path from a [ClipSnapshot] into clipboard history, shared by the keyboard
 * (window shown, resume, "save current clip") and the clipboard history screen. Callers check
 * enabled/paused and sensitive fields first.
 */
object ClipboardCapture {
    suspend fun capture(
        snapshot: ClipSnapshot?,
        settings: KeyboardSettings,
        repository: KeyboardRepository,
        retentionDays: Int = settings.clipboardRetentionDays,
    ): ClipboardCaptureOutcome {
        val text = snapshot?.text ?: return ClipboardCaptureOutcome.NoText
        if (ClipboardCutoff.shouldSkip(settings, snapshot.copiedAt)) return ClipboardCaptureOutcome.SkippedCleared
        return when (val decision = ClipboardHistoryPolicy.evaluate(text, snapshot.flaggedSensitive)) {
            is ClipboardCaptureDecision.Accept -> {
                repository.captureClipboard(
                    content = decision.content,
                    contentHash = decision.contentHash,
                    retentionDays = retentionDays
                )
                ClipboardCaptureOutcome.Saved
            }
            is ClipboardCaptureDecision.Reject -> ClipboardCaptureOutcome.Rejected(decision)
        }
    }

    /** Status line for an explicit capture. */
    fun message(outcome: ClipboardCaptureOutcome): String = when (outcome) {
        ClipboardCaptureOutcome.NoText -> "Clipboard is unavailable or has no plain text."
        ClipboardCaptureOutcome.SkippedCleared -> "Not saved: this clip was copied before you last deleted clipboard history. Copy it again to save it."
        is ClipboardCaptureOutcome.Rejected -> ClipboardHistoryPolicy.rejectionMessage(outcome.reason.reason)
        ClipboardCaptureOutcome.Saved -> "Saved locally. Duplicate clips collapse automatically."
    }
}
