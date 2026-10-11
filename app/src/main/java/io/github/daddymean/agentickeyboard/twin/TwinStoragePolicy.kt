package io.github.daddymean.agentickeyboard.twin

/**
 * KEYBOARD-024 storage policy (Hax decision, 2026-10-10 07:14 PT, for the legacy
 * purpose): the twin **never deletes Keith's history on its own**.
 *
 * - Every [WARNING_STEP_BYTES] (64 MB, then 128 MB, 192 MB, ...) Keith gets a
 *   one-time notice, and Legacy twin settings shows a standing warning. The remedy
 *   is export (slice 3), not deletion.
 * - The only hard stop is the phone itself running low: below [MIN_FREE_BYTES]
 *   (500 MB) of free storage, capture **pauses** and warns. Nothing is deleted, and
 *   capture resumes by itself once there is room again.
 */
object TwinStoragePolicy {
    const val WARNING_STEP_BYTES = 64L * 1024 * 1024
    const val MIN_FREE_BYTES = 500L * 1024 * 1024

    /** 0 below the first step, 1 from 64 MB, 2 from 128 MB, ... */
    fun tier(bytes: Long, step: Long = WARNING_STEP_BYTES): Int = (bytes / step).toInt()

    /** The tier to announce now, or null when this tier was already announced. */
    fun tierToAnnounce(bytes: Long, lastAnnounced: Int, step: Long = WARNING_STEP_BYTES): Int? =
        tier(bytes, step).takeIf { it >= 1 && it > lastAnnounced }

    fun canCapture(freeBytes: Long): Boolean = freeBytes >= MIN_FREE_BYTES

    fun sizeNotice(tier: Int, step: Long = WARNING_STEP_BYTES): String =
        "Your legacy twin now uses more than ${tier * step / (1024 * 1024)} MB on this phone. " +
            "Nothing is deleted automatically. Export (coming in the next twin release) will let you " +
            "move it off the phone."

    const val LOW_STORAGE_NOTICE =
        "Legacy twin paused: less than 500 MB free on this phone. Nothing was deleted. " +
            "Free up space and learning resumes automatically."
}

/**
 * Writes one filtered message under [TwinStoragePolicy]. Runs on the twin worker.
 * [freeBytes] and [notify] are injected so the policy can be tested; [notify] gets
 * a one-time message for Keith (a toast from the keyboard).
 */
class TwinWriter(
    private val store: TwinStore,
    private val prefs: TwinPreferences,
    private val freeBytes: () -> Long,
    private val notify: (String) -> Unit,
    private val warningStep: Long = TwinStoragePolicy.WARNING_STEP_BYTES
) {
    /** True when stored; false when capture is paused for low device storage. */
    fun write(text: String, packageName: String?, atMillis: Long): Boolean {
        if (!TwinStoragePolicy.canCapture(freeBytes())) {
            if (!prefs.isLowStoragePaused) {
                prefs.isLowStoragePaused = true
                notify(TwinStoragePolicy.LOW_STORAGE_NOTICE)
            }
            return false
        }
        if (prefs.isLowStoragePaused) prefs.isLowStoragePaused = false
        store.add(text, packageName, atMillis)
        TwinStoragePolicy.tierToAnnounce(store.fileBytes(), prefs.announcedSizeTier, warningStep)?.let { tier ->
            prefs.announcedSizeTier = tier
            notify(TwinStoragePolicy.sizeNotice(tier, warningStep))
        }
        return true
    }
}
