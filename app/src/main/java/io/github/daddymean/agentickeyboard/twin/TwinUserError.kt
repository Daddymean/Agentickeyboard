package io.github.daddymean.agentickeyboard.twin

/**
 * A twin error whose message is fixed text written for Keith (never entry text), so a
 * screen may show it. Any other exception gets a generic message on screen; its message
 * may hold content and is never shown or logged.
 */
class TwinUserError(message: String) : IllegalArgumentException(message) {
    companion object {
        fun userMessage(t: Throwable, fallback: String): String = (t as? TwinUserError)?.message ?: fallback
    }
}
