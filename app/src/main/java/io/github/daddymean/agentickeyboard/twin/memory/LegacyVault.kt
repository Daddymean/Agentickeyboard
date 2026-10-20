package io.github.daddymean.agentickeyboard.twin.memory

import io.github.daddymean.agentickeyboard.twin.TwinExportScrubber
import io.github.daddymean.agentickeyboard.twin.journal.JournalKinds
import io.github.daddymean.agentickeyboard.twin.journal.JournalRecord
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Where a memory came from: one allowed field of one journal record. */
data class SourceRef(val recordId: Long, val field: String) {
    val key: String
        get() = "journal:$recordId:${this.field}"
}

/**
 * One memory in the legacy vault. It exists only because Keith approved it.
 * [text] is what he approved (possibly edited); [sourceText] is the scrubbed journal
 * text it came from, kept so a citation can show the original.
 */
data class Memory(
    val id: String,
    val text: String,
    val source: SourceRef,
    val sourceText: String,
    val prompt: String?,
    val child: String?,
    val atMillis: Long,
    val approvedAtMillis: Long,
    val edited: Boolean
)

/** A pinned core-memory block (Letta's pattern): written only by Keith, always in context. */
data class CoreBlock(val label: String, val title: String, val value: String, val updatedAtMillis: Long)

/** A journal field waiting for Keith's review. Nothing here is retrievable. */
data class Candidate(val source: SourceRef, val text: String, val prompt: String?, val child: String?, val atMillis: Long)

data class VaultState(
    val memories: List<Memory> = emptyList(),
    /** Source keys Keith rejected: never offered again. */
    val rejected: Set<String> = emptySet(),
    val core: List<CoreBlock> = LegacyVault.CORE_LABELS.map { (l, t) -> CoreBlock(l, t, "", 0L) }
) {
    fun coreBlock(label: String): CoreBlock? = core.firstOrNull { it.label == label }
    fun memory(id: String): Memory? = memories.firstOrNull { it.id == id }
}

/**
 * KEYBOARD-030: the approval-gated legacy vault (Keith, relayed 2026-10-19 14:25 PT).
 *
 * - **Allowed fields only.** Candidates come from journal answers (`text`, `transcript`)
 *   and the values card sort (`top5`). Nothing else is learned: typed messages,
 *   instrument scores, settings, events, child-name settings and recordings are not.
 *   Every candidate is scrubbed with [TwinExportScrubber]; text it rejects is dropped.
 * - **Approval gate.** A candidate becomes a [Memory] only through [approve]. Pending
 *   candidates are computed, never stored, and can't be searched or cited.
 *   [reject] keeps a source out for good.
 * - **Core blocks** ("Who I am", "About my kids") are written only by Keith, capped at
 *   [CORE_LIMIT] characters, and always sit in the twin's context ([TwinContext]).
 * - Deleting a journal record removes every memory derived from it ([forgetRecord]).
 *
 * Pure logic; [MemoryStore] seals the state on disk.
 */
object LegacyVault {
    const val WHO_I_AM = "who_i_am"
    const val ABOUT_MY_KIDS = "about_my_kids"
    val CORE_LABELS = listOf(WHO_I_AM to "Who I am", ABOUT_MY_KIDS to "About my kids")
    const val CORE_LIMIT = 2000
    const val MEMORY_LIMIT = 4000

    /** The only journal fields the twin may learn from, by record kind. */
    val ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
        JournalKinds.ANSWER to setOf("text", "transcript"),
        JournalKinds.CARD_SORT to setOf("top5")
    )

    /** Fields waiting for review: allowed, scrubbed, not yet approved or rejected. */
    fun candidates(records: List<JournalRecord>, state: VaultState): List<Candidate> {
        val decided = state.rejected + state.memories.map { it.source.key }
        val out = ArrayList<Candidate>()
        for (r in records) {
            val fields = ALLOWED_FIELDS[r.kind] ?: continue
            for (field in fields.sorted()) {
                val ref = SourceRef(r.id, field)
                if (ref.key in decided) continue
                val raw = fieldText(r, field) ?: continue
                val clean = TwinExportScrubber.scrub(raw)?.trim()?.take(MEMORY_LIMIT)?.takeIf { it.isNotEmpty() } ?: continue
                out += Candidate(ref, clean, prompt(r), r.body.optString("child").takeIf { it.isNotBlank() }, r.atMillis)
            }
        }
        return out
    }

    /** Keith approves a candidate, optionally edited. The edit is scrubbed too. */
    fun approve(state: VaultState, c: Candidate, now: Long, editedText: String? = null, id: String = UUID.randomUUID().toString()): VaultState {
        require(c.source.key !in state.rejected && state.memories.none { it.source.key == c.source.key }) { "already decided" }
        val edited = editedText?.trim()?.takeIf { it.isNotEmpty() && it != c.text }
        val text = edited?.let { TwinExportScrubber.scrub(it)?.trim()?.take(MEMORY_LIMIT) } ?: if (edited == null) c.text else null
        requireNotNull(text) { "nothing left to save after removing identifiers" }
        val m = Memory(id, text, c.source, c.text, c.prompt, c.child, c.atMillis, now, edited != null)
        return state.copy(memories = state.memories + m)
    }

    fun reject(state: VaultState, c: Candidate): VaultState = state.copy(rejected = state.rejected + c.source.key)

    /** Removes an approved memory. Its source is then treated as rejected, so it isn't offered again. */
    fun remove(state: VaultState, memoryId: String): VaultState {
        val m = state.memory(memoryId) ?: return state
        return state.copy(memories = state.memories - m, rejected = state.rejected + m.source.key)
    }

    /** A journal record was deleted: drop everything derived from it. */
    fun forgetRecord(state: VaultState, recordId: Long): VaultState {
        val prefix = "journal:$recordId:"
        return state.copy(memories = state.memories.filterNot { it.source.recordId == recordId }, rejected = state.rejected.filterNot { it.startsWith(prefix) }.toSet())
    }

    fun setCore(state: VaultState, label: String, value: String, now: Long): VaultState {
        val title = CORE_LABELS.firstOrNull { it.first == label }?.second ?: throw IllegalArgumentException("unknown core block")
        require(value.length <= CORE_LIMIT) { "$title is limited to $CORE_LIMIT characters" }
        val block = CoreBlock(label, title, value.trim(), now)
        return state.copy(core = CORE_LABELS.map { (l, _) -> if (l == label) block else state.coreBlock(l) ?: CoreBlock(l, title(l), "", 0L) })
    }

    private fun title(label: String) = CORE_LABELS.first { it.first == label }.second

    private fun fieldText(r: JournalRecord, field: String): String? = when (val v = r.body.opt(field)) {
        is String -> v.takeIf { it.isNotBlank() }
        is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).takeIf(String::isNotBlank) }
            .takeIf { it.isNotEmpty() }?.let { "My top values: " + it.joinToString(", ") + "." }
        else -> null
    }

    private fun prompt(r: JournalRecord): String? =
        (r.body.optString("prompt").ifEmpty { r.body.optString("title") }).takeIf { it.isNotBlank() }
            ?: if (r.kind == JournalKinds.CARD_SORT) "Values Card Sort" else null

    // ---- JSON ------------------------------------------------------------------------

    fun toJson(s: VaultState): JSONObject = JSONObject()
        .put("v", 1)
        .put("memories", JSONArray(s.memories.map { m ->
            JSONObject().put("id", m.id).put("text", m.text).put("record", m.source.recordId).put("field", m.source.field)
                .put("source_text", m.sourceText).put("prompt", m.prompt ?: JSONObject.NULL).put("child", m.child ?: JSONObject.NULL)
                .put("at", m.atMillis).put("approved_at", m.approvedAtMillis).put("edited", m.edited)
        }))
        .put("rejected", JSONArray(s.rejected.sorted()))
        .put("core", JSONArray(s.core.map { JSONObject().put("label", it.label).put("value", it.value).put("updated_at", it.updatedAtMillis) }))

    fun fromJson(o: JSONObject): VaultState {
        val ms = o.optJSONArray("memories") ?: JSONArray()
        val memories = (0 until ms.length()).map { i ->
            val m = ms.getJSONObject(i)
            Memory(m.getString("id"), m.getString("text"), SourceRef(m.getLong("record"), m.getString("field")), m.getString("source_text"),
                m.optString("prompt").takeIf { !m.isNull("prompt") && it.isNotEmpty() }, m.optString("child").takeIf { !m.isNull("child") && it.isNotEmpty() },
                m.getLong("at"), m.getLong("approved_at"), m.optBoolean("edited"))
        }
        val rj = o.optJSONArray("rejected") ?: JSONArray()
        val cs = o.optJSONArray("core") ?: JSONArray()
        val saved = (0 until cs.length()).map { cs.getJSONObject(it) }.associateBy { it.getString("label") }
        val core = CORE_LABELS.map { (l, t) -> saved[l]?.let { CoreBlock(l, t, it.optString("value"), it.optLong("updated_at")) } ?: CoreBlock(l, t, "", 0L) }
        return VaultState(memories, (0 until rj.length()).map { rj.getString(it) }.toSet(), core)
    }
}
