package io.github.daddymean.agentickeyboard.util

/**
 * KEYBOARD-022: what the keyboard keeps about an incoming message. That is only
 * the estimated mood and when it expires. The message text is never stored
 * here, so it cannot leak into history, logs or the cloud.
 */
class IncomingMoodSession {
    companion object {
        /** Every gate must pass before the screen is read for a mood. */
        fun shouldRead(
            autoMoodOn: Boolean,
            appAllowed: Boolean,
            serviceConnected: Boolean,
            sensitiveField: Boolean,
            keyboardShown: Boolean
        ): Boolean = autoMoodOn && appAllowed && serviceConnected && !sensitiveField && keyboardShown
    }

    data class Badge(val mood: IncomingSentiment.Mood, val intensity: Float, val expiresAt: Long)

    var badge: Badge? = null
        private set

    /** Classifies [message] and keeps only its mood until [now] + [ttlMs]. */
    fun update(message: String?, now: Long, ttlMs: Long = VisibleContextPolicy.TTL_MS): Badge? {
        badge = message?.takeIf { it.isNotBlank() }?.let {
            IncomingSentiment.score(it).let { s -> Badge(s.mood, s.intensity, now + ttlMs) }
        }
        return badge
    }

    /** Keeps an already computed [score] (or nothing) until [now] + [ttlMs]. */
    fun set(score: IncomingSentiment.Score?, now: Long, ttlMs: Long = VisibleContextPolicy.TTL_MS): Badge? {
        badge = score?.let { Badge(it.mood, it.intensity, now + ttlMs) }
        return badge
    }

    /** The badge if it has not expired; an expired badge is dropped. */
    fun current(now: Long): Badge? {
        val b = badge ?: return null
        if (now >= b.expiresAt) badge = null
        return badge
    }

    fun clear() {
        badge = null
    }
}
