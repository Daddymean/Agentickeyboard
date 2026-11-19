package io.github.daddymean.agentickeyboard.twin

import io.github.daddymean.agentickeyboard.twin.journal.JournalKinds
import io.github.daddymean.agentickeyboard.twin.journal.JournalRecord
import io.github.daddymean.agentickeyboard.twin.journal.JournalStore
import io.github.daddymean.agentickeyboard.twin.memory.EncryptedVectorIndex
import io.github.daddymean.agentickeyboard.twin.memory.LegacyVault
import io.github.daddymean.agentickeyboard.twin.memory.MemoryIndex
import io.github.daddymean.agentickeyboard.twin.memory.MemoryStore
import io.github.daddymean.agentickeyboard.twin.memory.VaultState
import io.github.daddymean.agentickeyboard.twin.export.TwinExporter
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** The groups on the "What the twin has learned" screen, in display order. */
enum class LearnedGroup(val title: String) {
    MEMORIES("Approved memories"),
    CORE("Core memories"),
    PHRASES("Phrases picked up from your messages"),
    MESSAGES("Typed messages"),
    JOURNAL("Journal answers the twin learns from")
}

/**
 * One thing the twin keeps. [key] is `msg:<row id>`, `m:<memory id>`, `core:<label>`,
 * `journal:<record id>` or `phrase:<words>`. [sourcePackage] is the app it was typed in
 * (typed messages only); [source] is what the screen shows. [atMillis] is when it was
 * stored (0 for derived phrases). [why] says why it was learned, when that is recorded.
 */
data class LearnedItem(
    val key: String,
    val group: LearnedGroup,
    val text: String,
    val source: String,
    val sourcePackage: String?,
    val atMillis: Long,
    val why: String?
)

/** What a delete will remove (ids only), so the confirmation can say it before anything goes. */
data class ForgetPlan(
    val messageIds: Set<Long> = emptySet(),
    val memoryIds: Set<String> = emptySet(),
    val coreLabels: Set<String> = emptySet(),
    val journalIds: Set<Long> = emptySet(),
    /** Sealed recordings the journal answers in [journalIds] point to; they go with them. */
    val recordings: Int = 0
) {
    val isEmpty: Boolean get() = messageIds.isEmpty() && memoryIds.isEmpty() && coreLabels.isEmpty() && journalIds.isEmpty()
    operator fun plus(o: ForgetPlan) =
        ForgetPlan(messageIds + o.messageIds, memoryIds + o.memoryIds, coreLabels + o.coreLabels, journalIds + o.journalIds)

    /** "3 typed messages, 1 approved memory, 1 journal answer, 2 recordings" for the confirmation. Counts only, never content. */
    fun describe(): String = listOfNotNull(
        count(messageIds.size, "typed message"), count(memoryIds.size, "approved memory", "approved memories"),
        count(coreLabels.size, "core memory (cleared whole)", "core memories (each cleared whole)"),
        count(journalIds.size, "journal answer"), count(recordings, "recording")
    ).joinToString(", ").ifEmpty { "nothing" }

    private fun count(n: Int, one: String, many: String = one + "s") = if (n == 0) null else "$n ${if (n == 1) one else many}"
}

/** What a delete removed, per store. Counts only. */
data class ForgetResult(
    val messages: Int = 0,
    val memories: Int = 0,
    val vectors: Int = 0,
    val coreBlocks: Int = 0,
    val journalRecords: Int = 0,
    val derivedMemories: Int = 0,
    /** Steps (one per store or journal answer) that failed; 0 when everything went. */
    val failedSteps: Int = 0
)

/** The "never learn" lists the viewer can add to: Keith's never-learn apps and forgotten phrases. */
interface NeverLearnLists {
    fun addApp(packageName: String)
    /** Adds [phrase] (as a keyed MAC, never in clear). Returns false when it has no usable words. */
    fun addPhrase(phrase: String): Boolean

    companion object {
        fun of(prefs: TwinPreferences, key: PhraseKey = TwinLearning.phraseKey) = object : NeverLearnLists {
            override fun addApp(packageName: String) { prefs.secureApps = prefs.secureApps + packageName }
            override fun addPhrase(phrase: String): Boolean {
                val entry = NeverLearnPhrases.entry(phrase, key) ?: return false
                prefs.neverLearnPhrases = prefs.neverLearnPhrases + entry
                return true
            }
        }
    }
}

/**
 * The twin-memory viewer's model: lists everything the twin has learned, across every
 * store, and deletes it everywhere at once.
 *
 * Stores and what a delete reaches:
 * - [TwinStore] (`twin_store.db`): typed messages with app and time. Rows go with
 *   `secure_delete` on. Style stats and phrases are computed from these rows on demand
 *   and are never saved, so they change with them.
 * - [MemoryStore] (`memory.sealed`): approved memories and core blocks. A deleted memory's
 *   source is marked rejected, so the journal answer is never offered for review again.
 * - The FTS4 search index ([MemoryIndex]) is in memory only and rebuilt from the vault;
 *   [generation] tells open screens to drop their copy and rebuild.
 * - [EncryptedVectorIndex] (`memory-vectors.sealed`): every vector whose memory is gone
 *   is pruned, with or without the embedding model.
 * - [JournalStore]: a journal answer goes with its recordings and every memory made from it.
 * - Citations, "why the twin said this" and drift reports are computed from the vault when
 *   asked and never stored. Exports and dead-man archives read the stores when written, so
 *   anything deleted here is not in the next one.
 *
 * Nothing is logged: callers show counts only. Run every method on the twin worker.
 */
class TwinLedger(
    private val twin: TwinStore?,
    private val memory: MemoryStore?,
    private val journal: JournalStore?,
    private val vectors: EncryptedVectorIndex?,
    private val lists: NeverLearnLists? = null,
    private val clock: () -> Long = System::currentTimeMillis
) {
    // ---- listing -------------------------------------------------------------------------

    fun list(phrases: Int = PHRASES): List<LearnedItem> {
        val out = ArrayList<LearnedItem>()
        val vault = memory?.load() ?: VaultState()
        for (m in vault.memories.sortedByDescending { it.approvedAtMillis }) {
            out += LearnedItem(
                "m:${m.id}", LearnedGroup.MEMORIES, m.text, JOURNAL_SOURCE, null, m.approvedAtMillis,
                "You approved it from journal answer #${m.source.recordId}" + (m.prompt?.let { " to \"$it\"" } ?: "") +
                    (if (m.edited) ", after editing it" else "") + "."
            )
        }
        for (b in vault.core) if (b.value.isNotBlank()) out += LearnedItem(
            "core:${b.label}", LearnedGroup.CORE, b.value, b.title, null, b.updatedAtMillis,
            "You wrote it. It is always in the twin's context."
        )
        val stats = TwinStyleStats.Builder(top = phrases)
        val messages = ArrayList<LearnedItem>()
        twin?.forEach { e ->
            stats.add(e.text)
            messages += LearnedItem(
                "msg:${e.id}", LearnedGroup.MESSAGES, e.text, e.packageName ?: UNKNOWN_APP, e.packageName, e.atMillis,
                "You typed and sent it on this keyboard while twin learning was on."
            )
        }
        if (messages.isNotEmpty()) {
            val s = stats.build()
            for ((phrase, n) in (s.topTrigrams + s.topBigrams).sortedByDescending { it.second }) out += LearnedItem(
                "phrase:$phrase", LearnedGroup.PHRASES, phrase, "Your typed messages", null, 0L,
                "Used $n times in your messages; the twin copies phrases you use often."
            )
        }
        out += messages.sortedByDescending { it.atMillis }
        for (r in journal?.all().orEmpty().filter { it.kind in LegacyVault.ALLOWED_FIELDS }.sortedByDescending { it.atMillis }) {
            val text = journalText(r) ?: continue
            out += LearnedItem(
                "journal:${r.id}", LearnedGroup.JOURNAL, text, JOURNAL_SOURCE, null, r.atMillis,
                "You wrote or recorded it in the journal. The twin learns from it only after you approve it as a memory."
            )
        }
        return out
    }

    // ---- planning ------------------------------------------------------------------------

    /** Exactly the selected items. A phrase item stands for every message and memory holding it. */
    fun planKeys(keys: Collection<String>): ForgetPlan {
        var plan = ForgetPlan()
        val phrases = ArrayList<String>()
        val msgs = HashSet<Long>(); val mems = HashSet<String>(); val core = HashSet<String>(); val journals = HashSet<Long>()
        for (k in keys) {
            val v = k.substringAfter(':')
            when (k.substringBefore(':')) {
                "msg" -> v.toLongOrNull()?.let(msgs::add)
                "m" -> mems += v
                "core" -> core += v
                "journal" -> v.toLongOrNull()?.let(journals::add)
                "phrase" -> phrases += v
            }
        }
        plan += ForgetPlan(msgs, mems, core, journals)
        for (p in phrases) plan += planPhrase(p)
        return withRecordings(plan)
    }

    /** Every typed message from [packageName]. */
    fun planApp(packageName: String): ForgetPlan {
        val ids = HashSet<Long>()
        twin?.forEach { if (it.packageName == packageName) ids += it.id }
        return ForgetPlan(messageIds = ids)
    }

    /** Typed messages stored and memories approved in [fromMillis, toMillisExclusive). Core blocks and journal answers stay. */
    fun planRange(fromMillis: Long, toMillisExclusive: Long): ForgetPlan {
        fun inRange(t: Long) = t in fromMillis until toMillisExclusive
        val ids = HashSet<Long>()
        twin?.forEach { if (inRange(it.atMillis)) ids += it.id }
        val mems = memory?.load()?.memories.orEmpty().filter { inRange(it.approvedAtMillis) }.map { it.id }.toSet()
        return ForgetPlan(messageIds = ids, memoryIds = mems)
    }

    /**
     * Every place the twin still holds [phrase] (whole words, in a row): typed messages,
     * approved memories (their text or the original answer), core blocks (cleared whole),
     * and journal answers, which take their recordings and derived memories with them.
     * Journal settings records are never deleted here.
     */
    fun planPhrase(phrase: String): ForgetPlan {
        if (NeverLearnPhrases.words(phrase).isEmpty()) return ForgetPlan()
        fun has(t: String?) = t != null && NeverLearnPhrases.contains(t, phrase)
        val ids = HashSet<Long>()
        twin?.forEach { if (has(it.text)) ids += it.id }
        val vault = memory?.load()
        val mems = vault?.memories.orEmpty().filter { has(it.text) || has(it.sourceText) || has(it.prompt) }.map { it.id }.toSet()
        val core = vault?.core.orEmpty().filter { has(it.value) }.map { it.label }.toSet()
        val journals = journal?.all().orEmpty().filter { it.kind != JournalKinds.SETTINGS && freeText(it.body).any(::has) }.map { it.id }.toSet()
        return withRecordings(ForgetPlan(ids, mems, core, journals))
    }

    private fun withRecordings(plan: ForgetPlan): ForgetPlan {
        if (plan.journalIds.isEmpty()) return plan
        val n = journal?.all().orEmpty().filter { it.id in plan.journalIds }.sumOf { JournalStore.audioIds(it.body).size }
        return plan.copy(recordings = n)
    }

    // ---- deleting ------------------------------------------------------------------------

    /**
     * Deletes everything in [plan] from every store, then prunes leftover vectors.
     *
     * Journal answers use a tombstone: their memories go and their sources are marked
     * rejected *before* the row is deleted, and the tombstone is cleared only once the row is
     * gone (ids are AUTOINCREMENT, never reused). A crash or failure in between can't bring the
     * answer back for review. Stores can't share a transaction, so each step runs on its own;
     * [ForgetResult.failedSteps] counts the ones that failed, and the screen says so.
     */
    fun forget(plan: ForgetPlan): ForgetResult {
        var failed = 0
        fun <T> step(default: T, block: () -> T): T = runCatching(block).getOrElse { failed++; default }
        val messages = step(0) { twin?.deleteMany(plan.messageIds) ?: 0 }
        var memories = 0
        var core = 0
        var derived = 0
        var journals = 0
        val mem = memory
        if (mem != null && (plan.memoryIds.isNotEmpty() || plan.coreLabels.isNotEmpty() || plan.journalIds.isNotEmpty())) step(Unit) {
            mem.update { s0 ->
                var s = s0
                var m = 0; var c = 0; var d = 0
                for (id in plan.memoryIds) if (s.memory(id) != null) { s = LegacyVault.remove(s, id); m++ }
                for (label in plan.coreLabels) if (s.coreBlock(label)?.value?.isNotEmpty() == true) {
                    s = LegacyVault.setCore(s, label, "", clock()); c++
                }
                for (rid in plan.journalIds) {
                    val before = s.memories.size
                    s = LegacyVault.tombstoneRecord(s, rid)
                    d += before - s.memories.size
                }
                memories = m; core = c; derived = d
                s
            }
        }
        val gone = HashSet<Long>()
        for (rid in plan.journalIds) step(Unit) { if (journal?.delete(rid) == true) { journals++; gone += rid } }
        if (mem != null && gone.isNotEmpty()) step(Unit) { mem.update { s -> gone.fold(s) { acc, rid -> LegacyVault.forgetRecord(acc, rid) } } }
        val pruned = if (mem != null) step(0) { vectors?.prune(mem.load().memories.map { it.id }.toSet()) ?: 0 } else 0
        changed()
        return ForgetResult(messages, memories, pruned, core, journals, derived, failed)
    }

    fun forgetKeys(keys: Collection<String>): ForgetResult = forget(planKeys(keys))

    /** Deletes every message from [packageName]; with [neverAgain], adds it to "Never learn in these apps". */
    fun forgetApp(packageName: String, neverAgain: Boolean): ForgetResult {
        if (neverAgain) lists?.addApp(packageName)
        return forget(planApp(packageName))
    }

    fun forgetRange(fromMillis: Long, toMillisExclusive: Long): ForgetResult = forget(planRange(fromMillis, toMillisExclusive))

    /** Deletes every message and memory with [phrase]; with [neverAgain], the twin never stores it again. */
    fun forgetPhrase(phrase: String, neverAgain: Boolean): ForgetResult {
        if (neverAgain) lists?.addPhrase(phrase)
        return forget(planPhrase(phrase))
    }

    /** Every free-text string in a journal body, as the export scrubs them (structural keys skipped). */
    private fun freeText(body: JSONObject): List<String> {
        val out = ArrayList<String>()
        fun walk(v: Any?, key: String?) {
            when (v) {
                is JSONObject -> for (k in v.keys()) walk(v.get(k), k)
                is JSONArray -> for (i in 0 until v.length()) walk(v.get(i), key)
                is String -> if (key !in TwinExporter.STRUCTURAL && v.isNotBlank()) out += v
            }
        }
        walk(body, null)
        return out
    }

    private fun journalText(r: JournalRecord): String? {
        val fields = LegacyVault.ALLOWED_FIELDS[r.kind] ?: return null
        val parts = fields.sorted().mapNotNull { f ->
            when (val v = r.body.opt(f)) {
                is String -> v.takeIf { it.isNotBlank() }
                is JSONArray -> (0 until v.length()).mapNotNull { v.optString(it).takeIf(String::isNotBlank) }.takeIf { it.isNotEmpty() }?.joinToString(", ")
                else -> null
            }
        }
        val prompt = r.body.optString("prompt").ifEmpty { r.body.optString("title") }.takeIf { it.isNotBlank() }
            ?: if (r.kind == JournalKinds.CARD_SORT) "Values Card Sort" else null
        if (parts.isEmpty()) return null
        return (prompt?.let { "$it\n" } ?: "") + parts.joinToString("\n")
    }

    companion object {
        const val PHRASES = 10
        const val JOURNAL_SOURCE = "Journal"
        const val UNKNOWN_APP = "unknown app"

        private val gen = AtomicLong()

        /**
         * Bumped after every delete. A screen that holds a copy of the vault or a search index
         * compares it on resume and reloads, so a deleted item can't be shown, searched or cited.
         */
        val generation: Long get() = gen.get()

        fun changed() { gen.incrementAndGet() }

        /** The apps typed messages came from, with how many each. */
        fun apps(items: List<LearnedItem>): List<Pair<String, Int>> =
            items.filter { it.group == LearnedGroup.MESSAGES && it.sourcePackage != null }
                .groupingBy { it.sourcePackage!! }.eachCount().toList().sortedByDescending { it.second }

        /** Case- and accent-folded search over text, source, app and reason; every word must match a word's start. */
        fun search(items: List<LearnedItem>, query: String): List<LearnedItem> {
            val terms = MemoryIndex.tokens(query)
            if (terms.isEmpty()) return items
            return items.filter { item ->
                val words = MemoryIndex.tokens(listOfNotNull(item.text, item.source, item.sourcePackage, item.why).joinToString(" "))
                terms.all { t -> words.any { it.startsWith(t) } }
            }
        }
    }
}
