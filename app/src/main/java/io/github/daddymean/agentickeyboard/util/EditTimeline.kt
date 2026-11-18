package io.github.daddymean.agentickeyboard.util

/*
 * LAB-014: rewind a draft through its own edit history.
 *
 * Gboard's undo brings back only the last deletion, all or nothing; iOS shake
 * to undo is one step at a time and app-dependent. This keeps a short, in-memory
 * timeline of checkpoints of the draft the keyboard is editing and lets the user
 * jump back to any of them ("before I deleted that paragraph", "before the AI
 * rewrite"). Checkpoints are taken:
 *  - at word boundaries while typing (the draft just ended in a space/punctuation),
 *  - right before a deletion burst starts (the state that is about to be lost),
 *  - before any whole-text replacement the keyboard itself makes ([checkpoint]).
 *
 * Privacy: memory only, never written anywhere, cleared when the field closes,
 * and the service never feeds it from sensitive or password fields. The owner
 * decides; this class only holds strings it is given.
 */
class EditTimeline(private val maxCheckpoints: Int = 24, private val maxChars: Int = 1_000) {

    data class Version(val text: String, val label: String)

    private val checkpoints = ArrayDeque<String>()
    private var current: String = ""
    private var deleting = false

    val size: Int get() = checkpoints.size

    /** The draft changed to [text]. O(length) at most; called on the edit path. */
    fun observe(text: String) {
        if (text == current) return
        if (text.length > maxChars) { clear(); return }
        val previous = current
        val shrank = text.length < previous.length && previous.startsWith(text)
        if (shrank && !deleting) {
            push(previous) // about to lose this: keep it
            deleting = true
        } else if (!shrank) {
            deleting = false
            if (text.isNotEmpty() && isBoundary(text.last()) && text.length > previous.length) push(text)
        }
        current = text
    }

    /** Save [text] before the keyboard replaces it wholesale (AI apply, transform, rewind). */
    fun checkpoint(text: String = current) {
        if (text.length <= maxChars) push(text)
    }

    /** Older versions, newest first, without the current text or duplicates. */
    fun versions(limit: Int = 6): List<Version> {
        val seen = HashSet<String>()
        seen += current
        val out = ArrayList<Version>()
        for (i in checkpoints.indices.reversed()) {
            val t = checkpoints[i]
            if (!seen.add(t)) continue
            out += Version(t, describe(t, current))
            if (out.size == limit) break
        }
        return out
    }

    fun clear() {
        checkpoints.clear()
        current = ""
        deleting = false
    }

    private fun push(text: String) {
        if (text.isEmpty() || checkpoints.lastOrNull() == text) return
        checkpoints.addLast(text)
        while (checkpoints.size > maxCheckpoints) checkpoints.removeFirst()
    }

    private fun isBoundary(c: Char) = c.isWhitespace() || c in ".,!?;:)"

    companion object {
        /** A short chip label: what going back would restore or remove. */
        fun describe(old: String, now: String): String {
            var p = 0
            val max = minOf(old.length, now.length)
            while (p < max && old[p] == now[p]) p++
            var s = 0
            while (s < max - p && old[old.length - 1 - s] == now[now.length - 1 - s]) s++
            val restored = old.substring(p, old.length - s).trim()
            val removed = now.substring(p, now.length - s).trim()
            fun clip(x: String) = if (x.length <= 18) x else x.take(17) + "…"
            return when {
                restored.isNotEmpty() && removed.isEmpty() -> "+ ${clip(restored)}"
                restored.isEmpty() && removed.isNotEmpty() -> "− ${clip(removed)}"
                restored.isNotEmpty() -> "${clip(removed)} → ${clip(restored)}"
                else -> clip(old.trim())
            }
        }
    }
}
