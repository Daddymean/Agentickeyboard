package io.github.daddymean.agentickeyboard.twin

/**
 * KEYBOARD-044: when the optional quick-ask bubble may be on screen.
 *
 * The bubble belongs to the keyboard: it shows only while the keyboard is up on a
 * field the twin is allowed to see, so we always know what is underneath it. It is
 * hidden over password, incognito and other sensitive fields, over apps on the
 * never-learn list and over our own FLAG_SECURE screens ([TwinPrivacyFilter]).
 * It never reads the screen; tapping it only opens the twin's cited-answer screen.
 */
object TwinBubblePolicy {
    const val DEFAULT_ENABLED = false

    data class State(
        val enabled: Boolean,
        val overlayGranted: Boolean,
        val keyboardShown: Boolean,
        val sensitiveField: Boolean,
        val twinFieldBlocked: Boolean
    )

    fun visible(s: State): Boolean =
        s.enabled && s.overlayGranted && s.keyboardShown && !s.sensitiveField && !s.twinFieldBlocked
}
