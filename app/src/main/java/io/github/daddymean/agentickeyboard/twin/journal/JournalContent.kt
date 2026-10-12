package io.github.daddymean.agentickeyboard.twin.journal

import org.json.JSONArray
import org.json.JSONObject

/**
 * KEYBOARD-025: the legacy twin's interview content, shipped as assets under
 * `assets/twin/` (question-bank.json, instruments.json, voice-scripts.md).
 */
enum class Revisit { NONE, YEARLY, MILESTONE_AND_YEARLY;
    companion object {
        fun of(s: String) = when (s) {
            "yearly" -> YEARLY
            "per_milestone_and_yearly" -> MILESTONE_AND_YEARLY
            else -> NONE
        }
    }
}

/** One interview prompt from the question bank. */
data class Prompt(
    val id: String,
    val category: String,
    val categoryLabel: String,
    val text: String,
    val followUps: List<String>,
    val depth: Int,
    val answerMode: String,
    val emotionallyHeavy: Boolean,
    val perChild: Boolean,
    val placeholders: List<String>,
    val revisit: Revisit,
    val estMinutes: Int
) {
    /** Heavy for pacing: flagged emotionally heavy, or depth 3. */
    val heavy: Boolean get() = emotionallyHeavy || depth >= 3
}

object QuestionBank {
    fun parse(json: String): List<Prompt> {
        val items = JSONObject(json).getJSONArray("items")
        return (0 until items.length()).map { i ->
            val o = items.getJSONObject(i)
            Prompt(
                id = o.getString("id"),
                category = o.getString("category"),
                categoryLabel = o.optString("category_label", o.getString("category")),
                text = o.getString("prompt"),
                followUps = o.optJSONArray("follow_ups").strings(),
                depth = o.optInt("depth", 2),
                answerMode = o.optString("answer_mode", "either"),
                emotionallyHeavy = o.optBoolean("emotionally_heavy", false),
                perChild = o.optBoolean("per_child", false),
                placeholders = o.optJSONArray("placeholders").strings(),
                revisit = Revisit.of(o.optString("revisit", "none")),
                estMinutes = o.optInt("est_minutes", 5)
            )
        }
    }
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { getString(it) }

/** One voice-calibration session from voice-scripts.md: a title and its read-aloud text. */
data class VoiceSession(val number: Int, val title: String, val text: String)

object VoiceScripts {
    private val sessionHeader = Regex("^## Session (\\d+)(.*)$")

    /**
     * Splits the markdown at each "## Session N" heading; a session runs until the next
     * level-2 heading. The general recording advice ("Before you record") is returned
     * separately by [setup].
     */
    fun parse(markdown: String): List<VoiceSession> {
        val out = ArrayList<VoiceSession>()
        var current: Pair<Int, String>? = null
        val body = StringBuilder()
        fun flush() {
            current?.let { (n, title) -> out.add(VoiceSession(n, title, plain(body.toString()))) }
            body.setLength(0)
        }
        for (line in markdown.lines()) {
            val m = sessionHeader.find(line)
            if (m != null) {
                flush()
                val rest = m.groupValues[2].trim().trimStart(':').trim()
                current = m.groupValues[1].toInt() to ("Session ${m.groupValues[1]}" + if (rest.isNotEmpty()) ": ${plain(rest)}" else "")
            } else if (line.startsWith("## ")) {
                flush()
                current = null
            } else if (current != null) {
                body.append(line).append('\n')
            }
        }
        flush()
        return out
    }

    /** The "Before you record" advice. */
    fun setup(markdown: String): String {
        val start = markdown.indexOf("## Before you record")
        if (start < 0) return ""
        val end = markdown.indexOf("\n## ", start + 3).let { if (it < 0) markdown.length else it }
        return plain(markdown.substring(start + "## Before you record".length, end))
    }

    /** Markdown to readable plain text: headings, emphasis and rules removed. */
    fun plain(md: String): String = md.lines().joinToString("\n") { line ->
        line.replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("^---+$"), "")
            .replace("**", "")
            .replace(Regex("(?<![\\w*])\\*(?!\\s)([^*]+?)\\*(?![\\w*])"), "$1")
            .replace(Regex("(?<!\\w)_([^_]+)_(?!\\w)"), "$1")
    }.replace(Regex("\n{3,}"), "\n\n").trim()
}
