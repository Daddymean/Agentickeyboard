package io.github.daddymean.agentickeyboard.util

import java.security.MessageDigest

/** A bounded, explicit screen capture; no sender/direction is inferred. */
data class VisibleContext(
    val packageName: String,
    val windowId: Int,
    val lines: List<String>,
    val truncated: Boolean = false
) {
    val fingerprint: String
        get() {
            val bytes = "$packageName\u0000$windowId\u0000${lines.joinToString("\u0000")}".toByteArray()
            return MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
        }
}

data class VisibleTextNode(
    val packageName: String,
    val text: String,
    val top: Int,
    val left: Int,
    val visible: Boolean = true,
    val excluded: Boolean = false
)

/** Pure policy shared by the Android reader and tests. */
object VisibleContextPolicy {
    const val MAX_NODES = 256
    const val MAX_LINES = 40
    const val MAX_CHARS = 8_000
    const val TTL_MS = 60_000L

    fun build(packageName: String, windowId: Int, nodes: List<VisibleTextNode>): VisibleContext? {
        if (packageName.isBlank() || nodes.size > MAX_NODES) return null
        val texts = nodes.asSequence()
            .filter { it.packageName == packageName && it.visible && !it.excluded }
            .sortedWith(compareBy<VisibleTextNode> { it.top }.thenBy { it.left })
            .map { it.text.trim() }.filter { it.isNotEmpty() }.toList()
        if (texts.isEmpty()) return null
        // Keep the lower/latest visible part; repeated messages are not deduplicated.
        val lines = mutableListOf<String>()
        var remaining = MAX_CHARS
        for (text in texts.takeLast(MAX_LINES).asReversed()) {
            if (remaining <= 0) break
            val bounded = text.takeLast(remaining)
            lines.add(0, bounded)
            remaining -= bounded.length + 1
        }
        return VisibleContext(packageName, windowId, lines,
            truncated = texts.size > lines.size || texts.sumOf { it.length + 1 } > MAX_CHARS)
    }
}

/** One capture per editor session. Both confirmation and use revalidate the screen. */
class VisibleContextLease {
    private var snapshot: VisibleContext? = null
    private var deadline = 0L

    fun capture(value: VisibleContext, now: Long) {
        snapshot = value
        deadline = now + VisibleContextPolicy.TTL_MS
    }

    fun matches(current: VisibleContext?, now: Long): Boolean =
        snapshot != null && current != null && now < deadline &&
            snapshot!!.fingerprint == current.fingerprint

    fun clear() {
        snapshot = null
        deadline = 0
    }
}
