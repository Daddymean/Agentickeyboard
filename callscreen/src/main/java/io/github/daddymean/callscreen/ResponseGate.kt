package io.github.daddymean.callscreen

import java.util.concurrent.atomic.AtomicBoolean

/** One winner, monotonic elapsed time; neither late work nor exceptions can reject. */
class ResponseGate(
    private val deadlineMillis: Long,
    private val elapsedMillis: () -> Long,
    private val respond: (Action) -> Unit,
) {
    private val responded = AtomicBoolean(false)
    fun complete(action: Action) {
        if (responded.compareAndSet(false, true)) {
            respond(if (elapsedMillis() >= deadlineMillis) Action.ALLOW else action)
        }
    }
    fun evaluate(decision: () -> Action) {
        try { complete(decision()) } catch (_: RuntimeException) { failOpen() }
    }
    fun failOpen() = complete(Action.ALLOW)
}
